package com.earthforge.klaymore.script

import com.earthforge.klaymore.MinecraftDirectory
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.function.Consumer
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import net.minecraft.launchwrapper.Launch

object ScriptLoader {

  private val compileCache = mutableMapOf<String, CompiledScript>()
  private val lastModifiedCache = mutableMapOf<String, Long>()
  private val batchDirLoaders = mutableMapOf<String, ClassLoader>()

  /** common/ 目录编译产物的共享 ClassLoader，server/client 以此为父加载器 */
  @Volatile private var commonClassLoader: ClassLoader? = null

  /** common/ 编译输出目录，作为 server/client 编译时的 classpath */
  @Volatile private var commonClasspathDir: File? = null

  @Volatile private var commonLoaded: Boolean = false

  private val asyncExecutor =
      Executors.newSingleThreadExecutor { r ->
        Thread(r, "Klaymore-ScriptAsync").apply { isDaemon = true }
      }

  @JvmStatic
  fun loadScript(scriptFile: File): CompiledScript? {
    if (!scriptFile.exists() || !scriptFile.isFile) {
      ScriptErrorReporter.report("脚本文件不存在: ${scriptFile.absolutePath}")
      return null
    }

    val absolutePath = scriptFile.absolutePath
    val lastModified = scriptFile.lastModified()
    if (lastModifiedCache[absolutePath] == lastModified) {
      return compileCache[absolutePath]
    }

    ScriptClassCache.load(scriptFile, lastModified)?.let { cached ->
      compileCache[absolutePath] = cached
      lastModifiedCache[absolutePath] = lastModified
      return cached
    }

    val bridge = ScriptCompilerHolder.instance
    if (bridge == null) {
      ScriptErrorReporter.report(
          "脚本 ${scriptFile.name} 未预编译，且未安装 klaymore-compiler.jar。" + "请使用开发版编译脚本，或将预编译产物放入缓存目录。")
      return null
    }

    val dir = scriptFile.parentFile
    if (dir != null) {
      val batchKey = dir.absolutePath
      if (batchDirLoaders.containsKey(batchKey) || loadBatchDirectory(dir, bridge)) {
        compileCache[absolutePath]?.let {
          lastModifiedCache[absolutePath] = lastModified
          return it
        }
        lastModifiedCache[absolutePath] = lastModified
        return null
      }
    }
    val compileResult = bridge.compile(scriptFile)
    return when (compileResult) {
      is ResultWithDiagnostics.Success -> {
        val compiled = compileResult.value
        compileCache[absolutePath] = compiled
        lastModifiedCache[absolutePath] = lastModified
        // 落盘缓存（失败不影响本次使用，只打日志）
        ScriptClassCache.save(scriptFile, lastModified, compiled)
        compiled
      }
      is ResultWithDiagnostics.Failure -> {
        compileResult.reports
            .filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
            .forEach { diag ->
              System.err.println("[Klaymore Script] DIAGNOSTIC: ${diag.message}")
              try {
                val exField = diag.javaClass.getDeclaredField("exception")
                exField.isAccessible = true
                val ex = exField.get(diag) as? Throwable
                if (ex != null) {
                  ex.printStackTrace(System.err)
                }
              } catch (_: Throwable) {
                /* ignore */
              }
            }
        val errors =
            compileResult.reports
                .filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
                .joinToString("\n") { it.toString() }
        ScriptErrorReporter.report("编译失败: ${scriptFile.name}\n$errors")
        null
      }
      null -> null
    }
  }

  /** 批量编译目录下所有 .kt 脚本，共享 ClassLoader，同目录脚本可互相引用 */
  private fun loadBatchDirectory(directory: File, bridge: ScriptCompilerBridge): Boolean {
    val batchKey = directory.absolutePath
    if (batchDirLoaders.containsKey(batchKey)) return true

    ensureCommonLoaded(bridge)

    // 1. 先尝试磁盘批量缓存（整目录一起编译的产物，必须整体加载以保留跨文件引用）
    val cachedBytes = ScriptClassCache.loadBatchDirectory(directory)
    if (cachedBytes != null) {
      val parent = commonClassLoader ?: Launch.classLoader
      val classLoader = BatchClassLoader(cachedBytes, parent)
      batchDirLoaders[batchKey] = classLoader

      val scriptClassByFile = findScriptClassesByFile(cachedBytes, classLoader)
      var cachedCount = 0
      for ((fileName, kClass) in scriptClassByFile) {
        val file = File(directory, fileName)
        if (!file.exists()) continue
        val fileKey = file.absolutePath
        val compiled = CachedCompiledScript(kClass)
        compileCache[fileKey] = compiled
        lastModifiedCache[fileKey] = file.lastModified()
        cachedCount++
      }
      println(
          "[Klaymore] Batch loaded ${cachedCount} script(s) from disk cache (${directory.name}, " +
              "${cachedBytes.size} classes total)")
      return cachedCount > 0
    }

    // 2. 缓存未命中 → 实际编译
    val extraClasspath = commonClasspathDir?.let { listOf(it) } ?: emptyList()

    val result =
        try {
          bridge.compileBatch(directory, extraClasspath)
        } catch (t: Throwable) {
          System.err.println("[Klaymore] compileBatch threw: ${t.message}")
          null
        }

    if (result == null || !result.success) {
      val msg = result?.errorMessage ?: "批量编译失败"
      System.err.println("[Klaymore] Batch compile failed for ${directory.name}: $msg")
      return false
    }

    val parent = commonClassLoader ?: Launch.classLoader
    val classLoader = BatchClassLoader(result.classBytes, parent)
    batchDirLoaders[batchKey] = classLoader

    // 3. 落盘批量缓存（整目录产物），下次启动可直接从磁盘加载
    ScriptClassCache.saveBatchDirectory(directory, result.classBytes)

    val scriptClassByFile = findScriptClassesByFile(result.classBytes, classLoader)

    var cachedCount = 0
    for ((fileName, kClass) in scriptClassByFile) {
      val file = File(directory, fileName)
      if (!file.exists()) continue
      val fileKey = file.absolutePath
      val compiled = CachedCompiledScript(kClass)
      compileCache[fileKey] = compiled
      lastModifiedCache[fileKey] = file.lastModified()
      cachedCount++
    }

    println(
        "[Klaymore] Batch loaded ${cachedCount} script(s) from ${directory.name} " +
            "(${result.classBytes.size} classes total)")
    return cachedCount > 0
  }

  /** 确保 common/ 目录已编译并加载，幂等 */
  @Synchronized
  private fun ensureCommonLoaded(bridge: ScriptCompilerBridge) {
    if (commonLoaded) return
    commonLoaded = true

    val commonDir = MinecraftDirectory.getCommonScriptDirectory()
    if (!commonDir.isDirectory) {
      println("[Klaymore] common/ script directory not found, skipping common load.")
      return
    }

    val ktFiles =
        commonDir.listFiles { f -> f.isFile && f.extension.equals("kt", ignoreCase = true) }
    if (ktFiles == null || ktFiles.isEmpty()) {
      println("[Klaymore] common/ directory has no .kt files, skipping common load.")
      return
    }

    // 先尝试磁盘批量缓存
    val classBytes =
        ScriptClassCache.loadBatchDirectory(commonDir)
            ?: run {
              val result =
                  try {
                    bridge.compileBatch(commonDir)
                  } catch (t: Throwable) {
                    System.err.println("[Klaymore] common compileBatch threw: ${t.message}")
                    null
                  }

              if (result == null || !result.success) {
                val msg = result?.errorMessage ?: "common 编译失败"
                System.err.println("[Klaymore] Common script compile FAILED: $msg")
                return
              }
              // 落盘批量缓存
              ScriptClassCache.saveBatchDirectory(commonDir, result.classBytes)
              result.classBytes
            }

    val outDir = File(MinecraftDirectory.getCacheDirectory(), "common-classes")
    if (outDir.exists()) outDir.deleteRecursively()
    outDir.mkdirs()
    for ((className, bytes) in classBytes) {
      val classFile = File(outDir, className.replace('.', File.separatorChar) + ".class")
      classFile.parentFile?.mkdirs()
      classFile.writeBytes(bytes)
    }
    commonClasspathDir = outDir

    commonClassLoader = BatchClassLoader(classBytes, Launch.classLoader)

    println(
        "[Klaymore] Common scripts loaded: ${classBytes.size} classes -> shared ClassLoader ready")
  }

  /** 在 preInit 阶段实例化 common/ 目录脚本并调用 onRegister() */
  @JvmStatic
  @Synchronized
  fun runCommonRegistration(): Int {
    val bridge = ScriptCompilerHolder.instance
    if (bridge == null) {
      System.err.println(
          "[Klaymore] Cannot run common registration: ScriptCompilerBridge not available. " +
              "Install klaymore-compiler.jar or pre-compile scripts.")
      return 0
    }

    ensureCommonLoaded(bridge)

    val cl = commonClassLoader ?: return 0
    val commonDir = MinecraftDirectory.getCommonScriptDirectory()

    val classBytes = LinkedHashMap<String, ByteArray>()
    val outDir = commonClasspathDir ?: return 0
    outDir.walkTopDown().forEach { f ->
      if (f.isFile && f.extension.equals("class", ignoreCase = true)) {
        val relative = f.relativeTo(outDir).path
        val className =
            relative.removeSuffix(".class").replace(File.separatorChar, '.').replace('/', '.')
        classBytes[className] = f.readBytes()
      }
    }

    val scriptClasses = findScriptClassesByFile(classBytes, cl)
    var count = 0
    for ((fileName, kClass) in scriptClasses) {
      try {
        val instance = kClass.java.getDeclaredConstructor().newInstance()
        if (instance is KlaymoreScript) {
          instance.onRegister()
          count++
          println("[Klaymore] onRegister() executed for common script: $fileName")
        }
      } catch (t: Throwable) {
        System.err.println(
            "[Klaymore] Failed to run onRegister() for common script $fileName: ${t.message}")
        t.printStackTrace(System.err)
      }
    }
    return count
  }

  /** 从批量编译产物中找出所有 [KlaymoreScript] 子类，映射回源文件名 */
  private fun findScriptClassesByFile(
      classBytes: Map<String, ByteArray>,
      classLoader: ClassLoader
  ): Map<String, kotlin.reflect.KClass<*>> {
    val result = LinkedHashMap<String, kotlin.reflect.KClass<*>>()
    val originalLoader = Thread.currentThread().contextClassLoader
    try {
      Thread.currentThread().contextClassLoader = classLoader

      for ((className, bytes) in classBytes) {
        if (className.contains('$')) continue

        val clazz =
            try {
              classLoader.loadClass(className)
            } catch (_: Throwable) {
              continue
            }

        if (!KlaymoreScript::class.java.isAssignableFrom(clazz)) continue

        val sourceFile = parseSourceFileAttribute(bytes) ?: continue
        val fileName = File(sourceFile).name
        // 如果一个文件有多个 KlaymoreScript 子类，取第一个
        if (fileName !in result) {
          result[fileName] = clazz.kotlin
        }
      }
    } finally {
      Thread.currentThread().contextClassLoader = originalLoader
    }
    return result
  }

  /** 解析 class 文件常量池中的 SourceFile 属性 */
  private fun parseSourceFileAttribute(bytes: ByteArray): String? {
    return try {
      val dis = DataInputStream(ByteArrayInputStream(bytes))
      dis.skipBytes(8) // magic(4) + minor(2) + major(2)
      val count = dis.readUnsignedShort()
      val utf8s = arrayOfNulls<String>(count)

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
          7 -> dis.skipBytes(2) // Class
          3,
          4 -> dis.skipBytes(4) // Integer, Float
          5,
          6 -> {
            dis.skipBytes(8)
            i++ // Long, Double 占 2 个槽位
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
          else -> break
        }
        i++
      }

      // 跳过 access_flags, this_class, super_class
      dis.skipBytes(6)
      // 跳过 interfaces
      val ifaceCount = dis.readUnsignedShort()
      dis.skipBytes(ifaceCount * 2)
      // 跳过 fields
      skipMembers(dis)
      // 跳过 methods
      skipMembers(dis)

      // 解析 attributes，找 SourceFile
      val attrCount = dis.readUnsignedShort()
      for (a in 0 until attrCount) {
        val nameIndex = dis.readUnsignedShort()
        val attrLen = dis.readInt()
        val attrName = utf8s[nameIndex]
        if (attrName == "SourceFile" && attrLen == 2) {
          val sourceFileIndex = dis.readUnsignedShort()
          return utf8s[sourceFileIndex]
        } else {
          dis.skipBytes(attrLen)
        }
      }
      null
    } catch (_: Throwable) {
      null
    }
  }

  /** 跳过 fields 或 methods 表 */
  private fun skipMembers(dis: DataInputStream) {
    val count = dis.readUnsignedShort()
    for (m in 0 until count) {
      dis.skipBytes(6) // access_flags(2) + name_index(2) + descriptor_index(2)
      skipAttributes(dis)
    }
  }

  /** 跳过一个属性表。 */
  private fun skipAttributes(dis: DataInputStream) {
    val count = dis.readUnsignedShort()
    for (a in 0 until count) {
      dis.skipBytes(2) // attribute_name_index
      val len = dis.readInt()
      dis.skipBytes(len)
    }
  }

  @JvmStatic
  fun loadDirectory(directory: File, extension: String = "kt"): Int {
    if (!directory.exists() || !directory.isDirectory) return 0
    var count = 0
    directory.walkTopDown().forEach { file ->
      if (file.isFile && file.extension.equals(extension, ignoreCase = true)) {
        if (loadScript(file) != null) {
          count++
        }
      }
    }
    return count
  }

  /** 判断脚本文件是否已被处理（已缓存或已标记无脚本类） */
  @JvmStatic
  fun isScriptProcessed(scriptFile: File): Boolean {
    return lastModifiedCache[scriptFile.absolutePath] == scriptFile.lastModified()
  }

  @JvmStatic
  fun clearCache() {
    compileCache.clear()
    lastModifiedCache.clear()
    batchDirLoaders.clear()
    commonClassLoader = null
    commonClasspathDir = null
    commonLoaded = false
  }

  /** 根据脚本名称从缓存中获取已编译蓝图，用于 [spawnChild] 复用编译产物 */
  @JvmStatic
  fun getCachedCompiledScript(scriptName: String): CompiledScript? {
    // 精确匹配文件名
    compileCache.entries
        .firstOrNull { File(it.key).name == scriptName }
        ?.let {
          return it.value
        }
    // 匹配去掉扩展名的名称
    val baseName = scriptName.substringBeforeLast('.')
    compileCache.entries
        .firstOrNull { File(it.key).name.substringBeforeLast('.') == baseName }
        ?.let {
          return it.value
        }
    return null
  }

  @JvmStatic
  fun invalidateCache(scriptFile: File) {
    val path = scriptFile.absolutePath
    compileCache.remove(path)
    lastModifiedCache.remove(path)

    // 如果修改的是 common/ 目录下的脚本，需要清空所有缓存（common 变了，所有依赖它的目录都要重编译）
    val commonDir = MinecraftDirectory.getCommonScriptDirectory()
    val isCommon =
        try {
          scriptFile.canonicalPath.startsWith(commonDir.canonicalPath)
        } catch (_: Throwable) {
          false
        }
    if (isCommon) {
      println("[Klaymore] common script modified, clearing all batch caches: ${scriptFile.name}")
      batchDirLoaders.clear()
      commonClassLoader = null
      commonClasspathDir = null
      commonLoaded = false
      ScriptClassCache.invalidateBatchDirectory(commonDir)
    } else {
      // 单文件失效时，同时清除其所在目录的批量编译缓存，
      // 确保下次加载时整个目录重新编译（脚本间引用关系可能已变化）。
      scriptFile.parentFile?.let {
        batchDirLoaders.remove(it.absolutePath)
        ScriptClassCache.invalidateBatchDirectory(it)
      }
    }

    // 同时清掉磁盘缓存，确保下次加载重新编译（用于 /klaymore reload 等场景）
    ScriptClassCache.invalidate(scriptFile)
  }

  @JvmStatic
  fun loadScriptAsync(scriptFile: File, callback: Consumer<CompiledScript?>) {
    loadScriptAsync(scriptFile) { compiled -> callback.accept(compiled) }
  }

  @JvmStatic
  fun loadScriptAsync(scriptFile: File, callback: (CompiledScript?) -> Unit) {
    val path = scriptFile.absolutePath
    val lastModified = scriptFile.lastModified()
    // 缓存命中 → 统一通过 MainThreadDispatcher 回调
    if (lastModifiedCache[path] == lastModified) {
      MainThreadDispatcher.schedule(Runnable { callback(compileCache[path]) })
      return
    }
    // 内存未命中 → 尝试磁盘缓存
    val cached = ScriptClassCache.load(scriptFile, lastModified)
    if (cached != null) {
      compileCache[path] = cached
      lastModifiedCache[path] = lastModified
      MainThreadDispatcher.schedule(Runnable { callback(cached) })
      return
    }
    // 未命中 → 后台线程执行 loadScript（内部会尝试批量编译，失败回退单文件编译）
    asyncExecutor.submit {
      try {
        val compiled = loadScript(scriptFile)
        MainThreadDispatcher.schedule(Runnable { callback(compiled) })
      } catch (t: Throwable) {
        System.err.println("[Klaymore] loadScriptAsync failed: ${t.message}")
        MainThreadDispatcher.schedule(Runnable { callback(null) })
      }
    }
  }
}

/**
 * 批量编译产物的 ClassLoader。
 *
 * 同一目录下所有脚本编译出的类共享此加载器，因此脚本间可以互相引用彼此的类。 父加载器为 Launch.classLoader，保证 Minecraft/Forge/Kotlin 类可见。
 */
private class BatchClassLoader(private val classes: Map<String, ByteArray>, parent: ClassLoader) :
    ClassLoader(parent) {

  override fun findClass(name: String): Class<*> {
    // 1. 拦截 Forge / Minecraft 自身的包，强制委派给父加载器
    //    避免因重复加载导致包签名冲突
    if (name.startsWith("cpw.mods.fml.") ||
        name.startsWith("net.minecraftforge.") ||
        name.startsWith("net.minecraft.") ||
        name.startsWith("net.minecraft.launchwrapper.")) {
      return getParent().loadClass(name)
    }

    // 2. 如果是当前脚本编译产出的类，则自行定义
    classes[name]?.let { bytes ->
      return defineClass(name, bytes, 0, bytes.size)
    }

    // 3. 其他情况，回退到父加载器（Java 标准类等）
    return getParent().loadClass(name)
  }
}
