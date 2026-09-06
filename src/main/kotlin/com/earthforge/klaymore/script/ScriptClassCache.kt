package com.earthforge.klaymore.script

import com.earthforge.klaymore.MinecraftDirectory
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.reflect.KClass
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlinx.coroutines.runBlocking
import net.minecraft.launchwrapper.Launch

/**
 * 脚本编译产物的磁盘缓存。
 *
 * 每次游戏重启，内存中的 compileCache 都会丢失，导致脚本需要重新编译（Kotlin 编译器 冷启动 + 全量编译一个脚本通常几百 ms ~ 数秒）。本模块把编译后的 .class
 * 字节码落盘， 下次启动时只要脚本文件的 lastModified 没变，就直接从磁盘读回字节码、defineClass， 跳过编译，挂载耗时降到 <1ms。
 *
 * 缓存目录：<mcRoot>/.klaymore-cache/script-class-cache/<md5(脚本绝对路径)>/ ├─ meta.json # 记录
 * scriptPath / lastModified / mainClassName / classNames ├─ <MainClass>.class ├─ <MainClass$Inner>.class └─ ...
 *
 * 关键实现点：
 * - CompiledScript 是 kotlin scripting 的接口，只有 getClass() 会被上层用到 （见
 *   ScriptContainerFactory.instantiateScript），所以我们用一个 CachedCompiledScript 包装已加载的 KClass 即可无缝替换。
 * - 提取编译产物字节码：优先反射 CompiledScript / 其 ClassLoader 中的 Map<String, ByteArray> 字段（kotlin-scripting-jvm
 *   的 BasicCompiledScript 内部实现）； 拿不到时回退到 getResourceAsStream 读常量池闭包内的所有相关类。
 */
object ScriptClassCache {

  private val gson: Gson = GsonBuilder().create()

  private data class CacheMeta(
      val scriptPath: String,
      val lastModified: Long,
      val mainClassName: String,
      val classNames: List<String>
  )

  private fun getCacheRoot(): File? {
    return try {
      // 缓存放在 <mcRoot>/.klaymore-cache/script-class-cache/，与脚本目录分开，
      // 避免污染源码目录（之前放在 <scriptDir>/.script-class-cache，非常丑陋）。
      val cacheRoot = File(MinecraftDirectory.getGlobalCacheDirectory(), "script-class-cache")
      if (!cacheRoot.exists() && !cacheRoot.mkdirs()) {
        System.err.println(
            "[Klaymore ScriptCache] WARN: cannot mkdir cache root: ${cacheRoot.absolutePath}")
        return null
      }
      // 清理旧位置：之前缓存在 <scriptDir>/.script-class-cache，迁移后顺手删掉，
      // 让脚本目录恢复干净。失败忽略，不影响功能。
      try {
        val legacy = File(PersistenceStorage.getScriptDirectory(), ".script-class-cache")
        if (legacy.exists()) {
          legacy.deleteRecursively()
          println("[Klaymore ScriptCache] cleaned up legacy cache dir: ${legacy.absolutePath}")
        }
      } catch (_: Throwable) {
        /* ignore */
      }
      cacheRoot
    } catch (t: Throwable) {
      System.err.println("[Klaymore ScriptCache] failed to resolve cache root: ${t.message}")
      null
    }
  }

  private fun cacheKey(scriptFile: File): String {
    val path = scriptFile.absolutePath
    val md = MessageDigest.getInstance("MD5")
    val bytes = md.digest(path.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
  }

  private fun cacheDir(scriptFile: File): File? {
    val root = getCacheRoot() ?: return null
    return File(root, cacheKey(scriptFile))
  }

  /**
   * 尝试从磁盘缓存加载已编译脚本。
   *
   * @return 命中且有效 → CachedCompiledScript；否则 null（回退到重新编译）
   */
  fun load(scriptFile: File, lastModified: Long): CompiledScript? {
    val dir = cacheDir(scriptFile) ?: return null
    if (!dir.isDirectory) return null

    val metaFile = File(dir, "meta.json")
    if (!metaFile.isFile) return null

    val meta =
        try {
          metaFile.bufferedReader(Charsets.UTF_8).use { gson.fromJson(it, CacheMeta::class.java) }
        } catch (t: Throwable) {
          System.err.println(
              "[Klaymore ScriptCache] failed to parse meta for ${scriptFile.name}: ${t.message}")
          return null
        } ?: return null

    if (meta.lastModified != lastModified) return null

    val classes = LinkedHashMap<String, ByteArray>()
    for (className in meta.classNames) {
      val classFile = File(dir, "$className.class")
      if (!classFile.isFile) {
        System.err.println(
            "[Klaymore ScriptCache] missing class file '$className' for ${scriptFile.name}, recompiling")
        return null
      }
      classes[className] = classFile.readBytes()
    }

    return try {
      val classLoader = ScriptCacheClassLoader(classes, Launch.classLoader)
      val mainClass = classLoader.loadClass(meta.mainClassName)
      val kClass = mainClass.kotlin
      println(
          "[Klaymore ScriptCache] HIT ${scriptFile.name} (${classes.size} classes, loaded from disk)")
      CachedCompiledScript(kClass)
    } catch (t: Throwable) {
      System.err.println(
          "[Klaymore ScriptCache] failed to load cached classes for ${scriptFile.name}: ${t.message}")
      t.printStackTrace(System.err)
      null
    }
  }

  /** 把编译产物落盘。失败不抛出，只返回 false 并打日志（编译本身已成功，缓存失败不应阻断流程）。 */
  fun save(scriptFile: File, lastModified: Long, compiled: CompiledScript): Boolean {
    val originalLoader = Thread.currentThread().contextClassLoader
    return try {
      Thread.currentThread().contextClassLoader = Launch.classLoader
      try {
        val evalConfig = ScriptEvaluationConfiguration {}
        val classResult = runBlocking { compiled.getClass(evalConfig) }
        val kClass =
            when (classResult) {
              is ResultWithDiagnostics.Success -> classResult.value
              else -> {
                System.err.println(
                    "[Klaymore ScriptCache] cannot get class from compiled script for " +
                        "${scriptFile.name}, skip cache save")
                return false
              }
            }
        val mainClass = kClass.java
        val allBytes = extractScriptClassBytes(compiled, mainClass)
        if (allBytes.isEmpty()) {
          System.err.println(
              "[Klaymore ScriptCache] no class bytes extracted for ${scriptFile.name}, skip cache save")
          return false
        }

        val dir = cacheDir(scriptFile) ?: return false
        if (!dir.exists() && !dir.mkdirs()) {
          System.err.println("[Klaymore ScriptCache] cannot mkdir cache dir: ${dir.absolutePath}")
          return false
        }

        // 清理旧的 .class 文件（meta.json 会被覆盖）
        dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("class", ignoreCase = true) }
            ?.forEach { it.delete() }

        val classNames = allBytes.keys.sorted()
        for ((name, bytes) in allBytes) {
          File(dir, "$name.class").writeBytes(bytes)
        }

        val meta =
            CacheMeta(
                scriptPath = scriptFile.absolutePath,
                lastModified = lastModified,
                mainClassName = mainClass.name,
                classNames = classNames)
        File(dir, "meta.json").bufferedWriter(Charsets.UTF_8).use { gson.toJson(meta, it) }

        println(
            "[Klaymore ScriptCache] saved ${allBytes.size} classes for ${scriptFile.name} -> ${dir.absolutePath}")
        true
      } finally {
        Thread.currentThread().contextClassLoader = originalLoader
      }
    } catch (t: Throwable) {
      System.err.println(
          "[Klaymore ScriptCache] failed to save cache for ${scriptFile.name}: ${t.message}")
      false
    }
  }

  /** 删除某个脚本的缓存条目（脚本被修改 / reload 时调用）。 */
  fun invalidate(scriptFile: File) {
    try {
      val dir = cacheDir(scriptFile) ?: return
      if (dir.exists()) dir.deleteRecursively()
    } catch (t: Throwable) {
      System.err.println(
          "[Klaymore ScriptCache] failed to invalidate cache for ${scriptFile.name}: ${t.message}")
    }
  }

  // ----------------------------------------------------------------------------------
  //  从 CompiledScript / 其 ClassLoader 中提取所有脚本生成类的字节码
  // ----------------------------------------------------------------------------------

  /**
   * 主入口：反射 CompiledScript 及其 ClassLoader 上所有 Map<String, ByteArray> 字段 （kotlin-scripting-jvm
   * 编译产物的标准存储方式），合并后提取脚本相关类。 若主类字节码缺失，则用 getResourceAsStream 兜底读取。
   */
  private fun extractScriptClassBytes(
      compiled: CompiledScript,
      mainClass: Class<*>
  ): Map<String, ByteArray> {
    val mainName = mainClass.name

    // 收集 compiled 对象及其 ClassLoader 上所有 Map<String, ByteArray> 字段并合并
    // （不同版本的 kotlin-scripting-jvm 可能把主类和内部类存在不同字段里）
    val allBytes = LinkedHashMap<String, ByteArray>()
    collectClassByteMapsInto(compiled, allBytes)
    mainClass.classLoader?.let { collectClassByteMapsInto(it, allBytes) }

    // 资源路径形式的 key 可能带 ".class" 后缀，统一去掉得到二进制类名
    val normalized = LinkedHashMap<String, ByteArray>()
    for ((name, bytes) in allBytes) {
      val className = name.removeSuffix(".class")
      normalized[className] = bytes
    }

    // 只保留脚本自身生成的类：主类 + 主类的内部/匿名/lambda 类
    val scriptClasses = LinkedHashMap<String, ByteArray>()
    for ((name, bytes) in normalized) {
      if (name == mainName || name.startsWith("$mainName$")) {
        scriptClasses[name] = bytes
      }
    }

    // 主类必须存在；若反射没拿到，用 classLoader.getResourceAsStream 兜底读
    if (mainName !in scriptClasses) {
      val mainBytes = readClassBytesViaResource(mainClass)
      if (mainBytes != null) {
        scriptClasses[mainName] = mainBytes
      }
    }

    // 仍然拿不到主类 → 完全回退到资源扫描（BFS + 常量池闭包）
    if (mainName !in scriptClasses) {
      return collectViaResources(mainClass)
    }

    return scriptClasses
  }

  /** 把 target 对象（含父类）上所有 Map<String, ByteArray> 字段的内容合并进 dst。 */
  private fun collectClassByteMapsInto(target: Any, dst: MutableMap<String, ByteArray>) {
    var clazz: Class<*>? = target.javaClass
    while (clazz != null) {
      for (field in clazz.declaredFields) {
        if (!Map::class.java.isAssignableFrom(field.type)) continue
        try {
          field.isAccessible = true
          val value = field.get(target) as? Map<*, *> ?: continue
          if (value.isEmpty()) continue
          for ((k, v) in value) {
            if (k is String && v is ByteArray) {
              dst[k] = v
            }
          }
        } catch (_: Throwable) {
          // 这个字段拿不到，试下一个
        }
      }
      clazz = clazz.superclass
    }
  }

  /** 通过 classLoader.getResourceAsStream 读取单个类的字节码。 */
  private fun readClassBytesViaResource(clazz: Class<*>): ByteArray? {
    val classLoader = clazz.classLoader ?: return null
    return try {
      classLoader.getResourceAsStream(clazz.name.replace('.', '/') + ".class")?.readBytes()
    } catch (_: Throwable) {
      null
    }
  }

  /** 兜底方案：通过 classLoader.getResourceAsStream 逐个读 .class，并扫描常量池发现内部类。 */
  private fun collectViaResources(mainClass: Class<*>): Map<String, ByteArray> {
    val classLoader = mainClass.classLoader ?: return emptyMap()
    val mainName = mainClass.name
    val result = LinkedHashMap<String, ByteArray>()
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    queue.add(mainName)

    while (queue.isNotEmpty()) {
      val name = queue.removeFirst()
      if (!visited.add(name)) continue

      val bytes =
          try {
            classLoader.getResourceAsStream(name.replace('.', '/') + ".class")?.readBytes()
          } catch (_: Throwable) {
            null
          } ?: continue

      result[name] = bytes

      val referenced = parseConstantPoolClassNames(bytes)
      for (ref in referenced) {
        if (ref == mainName || ref.startsWith("$mainName$")) {
          if (ref !in visited) queue.add(ref)
        }
      }
    }
    return result
  }

  /** 解析 class 文件常量池，返回所有被引用的类名（点分形式）。 */
  private fun parseConstantPoolClassNames(bytes: ByteArray): Set<String> {
    val result = mutableSetOf<String>()
    try {
      val dis = DataInputStream(ByteArrayInputStream(bytes))
      dis.skipBytes(8) // magic(4) + minor(2) + major(2)
      val count = dis.readUnsignedShort()
      val utf8s = arrayOfNulls<String>(count)
      val classRefs = mutableListOf<Int>()

      var i = 1
      while (i < count) {
        val tag = dis.readUnsignedByte()
        when (tag) {
          1 -> { // Utf8
            val len = dis.readUnsignedShort()
            val buf = ByteArray(len)
            dis.readFully(buf)
            utf8s[i] = String(buf, Charsets.UTF_8)
          }
          7 -> { // Class
            classRefs.add(dis.readUnsignedShort())
          }
          3,
          4 -> dis.skipBytes(4) // Integer, Float
          5,
          6 -> { // Long, Double（占 2 个常量池槽位）
            dis.skipBytes(8)
            i++
          }
          8 -> dis.skipBytes(2) // String
          9,
          10,
          11,
          12 -> dis.skipBytes(4) // Fieldref, Methodref, InterfaceMethodref, NameAndType
          15 -> dis.skipBytes(3) // MethodHandle
          16 -> dis.skipBytes(2) // MethodType
          17,
          18 -> dis.skipBytes(4) // Dynamic, InvokeDynamic
          19,
          20 -> dis.skipBytes(2) // Module, Package
          else -> break // 未知 tag，无法继续解析
        }
        i++
      }

      for (nameIndex in classRefs) {
        if (nameIndex in 1 until count) {
          utf8s[nameIndex]?.let { result.add(it.replace('/', '.')) }
        }
      }
    } catch (_: Throwable) {
      // 解析失败就返回已收集的部分
    }
    return result
  }
}

/** 加载磁盘缓存字节码的 ClassLoader，父加载器设为 Launch.classLoader 以可见所有 MC/Forge/Kotlin 类。 */
private class ScriptCacheClassLoader(
    private val classes: Map<String, ByteArray>,
    parent: ClassLoader
) : ClassLoader(parent) {
  override fun findClass(name: String): Class<*> {
    val bytes = classes[name] ?: throw ClassNotFoundException(name)
    return defineClass(name, bytes, 0, bytes.size)
  }
}

/** 用已加载的 KClass 包装成 CompiledScript，供上层（instantiateScript 等）直接使用。 */
private class CachedCompiledScript(private val kClass: KClass<*>) : CompiledScript {
  override val otherScripts: List<CompiledScript> = emptyList()

  // 上层只用到 getClass()，compilationConfiguration 给个空配置即可
  override val compilationConfiguration: ScriptCompilationConfiguration =
      ScriptCompilationConfiguration {}

  override suspend fun getClass(
      scriptEvaluationConfiguration: ScriptEvaluationConfiguration?
  ): ResultWithDiagnostics<KClass<*>> {
    return ResultWithDiagnostics.Success(kClass)
  }
}
