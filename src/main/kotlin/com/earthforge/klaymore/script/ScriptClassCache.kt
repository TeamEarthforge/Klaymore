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
 * 脚本编译产物的磁盘缓存。重启后从磁盘加载 .class 字节码，跳过编译。缓存目录：<mcRoot>/klaymore/cache/script-class-cache/<md5(脚本路径)>/
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
      val cacheRoot = File(MinecraftDirectory.getCacheDirectory(), "script-class-cache")
      if (!cacheRoot.exists() && !cacheRoot.mkdirs()) {
        System.err.println(
            "[Klaymore ScriptCache] WARN: cannot mkdir cache root: ${cacheRoot.absolutePath}")
        return null
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

  /** 从磁盘缓存加载已编译脚本 */
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

  /** 将编译产物写入磁盘缓存 */
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

  /** 删除某个脚本的缓存条目 */
  fun invalidate(scriptFile: File) {
    try {
      val dir = cacheDir(scriptFile) ?: return
      if (dir.exists()) dir.deleteRecursively()
    } catch (t: Throwable) {
      System.err.println(
          "[Klaymore ScriptCache] failed to invalidate cache for ${scriptFile.name}: ${t.message}")
    }
  }

  // ====================================================================
  //  目录级批量缓存
  // ====================================================================
  // 批量编译（整个目录一起编译）的产物需要整体落盘，因为同目录脚本之间
  // 存在互相引用，必须共享同一个 ClassLoader 才能正确解析。
  // 缓存目录：<mcRoot>/klaymore/cache/script-class-cache/batch-<md5(目录路径)>/

  private data class BatchCacheMeta(val dirPath: String, val sourceFiles: Map<String, Long>)

  private fun batchCacheDir(directory: File): File? {
    val root = getCacheRoot() ?: return null
    return File(root, "batch-" + cacheKey(directory))
  }

  /** 从磁盘批量缓存加载整个目录的编译产物。若任源文件变动则返回 null。 */
  fun loadBatchDirectory(directory: File): Map<String, ByteArray>? {
    val dir = batchCacheDir(directory) ?: return null
    if (!dir.isDirectory) return null

    val metaFile = File(dir, "batch-meta.json")
    if (!metaFile.isFile) return null

    val meta =
        try {
          metaFile.bufferedReader(Charsets.UTF_8).use {
            gson.fromJson(it, BatchCacheMeta::class.java)
          }
        } catch (t: Throwable) {
          System.err.println(
              "[Klaymore ScriptCache] failed to parse batch meta for ${directory.name}: ${t.message}")
          return null
        } ?: return null

    val ktFiles =
        directory.listFiles { f -> f.isFile && f.extension.equals("kt", ignoreCase = true) }
            ?: return null
    val currentFiles = ktFiles.associate { it.name to it.lastModified() }
    if (currentFiles != meta.sourceFiles) {
      println(
          "[Klaymore ScriptCache] BATCH MISS ${directory.name} (source files changed, recompiling)")
      return null
    }

    val classFiles =
        dir.listFiles { f -> f.isFile && f.extension.equals("class", ignoreCase = true) }
            ?: return null
    val classes = LinkedHashMap<String, ByteArray>()
    for (cf in classFiles) {
      val className = cf.nameWithoutExtension
      classes[className] = cf.readBytes()
    }
    if (classes.isEmpty()) return null

    println(
        "[Klaymore ScriptCache] BATCH HIT ${directory.name} (${classes.size} classes from disk)")
    return classes
  }

  /** 将整个目录的批量编译产物写入磁盘缓存 */
  fun saveBatchDirectory(directory: File, classBytes: Map<String, ByteArray>): Boolean {
    return try {
      val dir = batchCacheDir(directory) ?: return false
      if (dir.exists()) dir.deleteRecursively()
      if (!dir.mkdirs()) {
        System.err.println(
            "[Klaymore ScriptCache] cannot mkdir batch cache dir: ${dir.absolutePath}")
        return false
      }

      for ((name, bytes) in classBytes) {
        File(dir, "$name.class").writeBytes(bytes)
      }

      val ktFiles =
          directory.listFiles { f -> f.isFile && f.extension.equals("kt", ignoreCase = true) }
              ?: emptyArray()
      val sourceFiles = ktFiles.associate { it.name to it.lastModified() }
      val meta = BatchCacheMeta(dirPath = directory.absolutePath, sourceFiles = sourceFiles)
      File(dir, "batch-meta.json").bufferedWriter(Charsets.UTF_8).use { gson.toJson(meta, it) }

      println(
          "[Klaymore ScriptCache] BATCH saved ${classBytes.size} classes for ${directory.name} -> ${dir.absolutePath}")
      true
    } catch (t: Throwable) {
      System.err.println(
          "[Klaymore ScriptCache] failed to save batch cache for ${directory.name}: ${t.message}")
      false
    }
  }

  /** 删除某个目录的批量缓存条目 */
  fun invalidateBatchDirectory(directory: File) {
    try {
      val dir = batchCacheDir(directory) ?: return
      if (dir.exists()) dir.deleteRecursively()
    } catch (t: Throwable) {
      System.err.println(
          "[Klaymore ScriptCache] failed to invalidate batch cache for ${directory.name}: ${t.message}")
    }
  }

  /** 从 CompiledScript 及其 ClassLoader 中提取所有脚本生成类的字节码 */
  private fun extractScriptClassBytes(
      compiled: CompiledScript,
      mainClass: Class<*>
  ): Map<String, ByteArray> {
    val mainName = mainClass.name

    val allBytes = LinkedHashMap<String, ByteArray>()
    collectClassByteMapsInto(compiled, allBytes)
    mainClass.classLoader?.let { collectClassByteMapsInto(it, allBytes) }

    val normalized = LinkedHashMap<String, ByteArray>()
    for ((name, bytes) in allBytes) {
      val className = name.removeSuffix(".class").replace('/', '.')
      normalized[className] = bytes
    }

    val scriptClasses = LinkedHashMap<String, ByteArray>()
    for ((name, bytes) in normalized) {
      if (name == mainName || name.startsWith("$mainName$")) {
        scriptClasses[name] = bytes
      }
    }

    if (mainName !in scriptClasses) {
      val mainBytes = readClassBytesViaResource(mainClass)
      if (mainBytes != null) {
        scriptClasses[mainName] = mainBytes
      }
    }

    if (mainName !in scriptClasses) {
      return collectViaResources(mainClass)
    }

    val viaResources = collectViaResources(mainClass)
    for ((name, bytes) in viaResources) {
      scriptClasses.putIfAbsent(name, bytes)
    }

    return scriptClasses
  }

  /** 合并 target 及其父类上所有 Map<String, ByteArray> 字段 */
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
        } catch (_: Throwable) {}
      }
      clazz = clazz.superclass
    }
  }

  /** 通过 getResourceAsStream 读取单个类的字节码 */
  private fun readClassBytesViaResource(clazz: Class<*>): ByteArray? {
    val classLoader = clazz.classLoader ?: return null
    return try {
      classLoader.getResourceAsStream(clazz.name.replace('.', '/') + ".class")?.readBytes()
    } catch (_: Throwable) {
      null
    }
  }

  /** 通过 getResourceAsStream 逐个读取 .class 并扫描常量池发现内部类 */
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

/** 加载磁盘缓存字节码的 ClassLoader */
private class ScriptCacheClassLoader(
    private val classes: Map<String, ByteArray>,
    parent: ClassLoader
) : ClassLoader(parent) {
  override fun findClass(name: String): Class<*> {
    val bytes = classes[name] ?: throw ClassNotFoundException(name)
    return defineClass(name, bytes, 0, bytes.size)
  }
}

/** 用已加载的 KClass 包装成 CompiledScript */
internal class CachedCompiledScript(private val kClass: KClass<*>) : CompiledScript {
  override val otherScripts: List<CompiledScript> = emptyList()
  override val compilationConfiguration: ScriptCompilationConfiguration =
      ScriptCompilationConfiguration {}

  override suspend fun getClass(
      scriptEvaluationConfiguration: ScriptEvaluationConfiguration?
  ): ResultWithDiagnostics<KClass<*>> {
    return ResultWithDiagnostics.Success(kClass)
  }
}
