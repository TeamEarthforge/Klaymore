package com.earthforge.klaymore.script

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

  /** 已批量编译的目录 → 该目录所有类共享的 ClassLoader（同目录脚本可互相引用）。 */
  private val batchDirLoaders = mutableMapOf<String, ClassLoader>()

  /** 异步编译/加载专用单线程执行器，避免多线程并发修改缓存。 */
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

    // 内存未命中 → 尝试磁盘缓存（.class 字节码，跨游戏重启复用）
    ScriptClassCache.load(scriptFile, lastModified)?.let { cached ->
      compileCache[absolutePath] = cached
      lastModifiedCache[absolutePath] = lastModified
      return cached
    }

    // 缓存全部未命中 → 尝试通过编译器桥接编译
    val bridge = ScriptCompilerHolder.instance
    if (bridge == null) {
      ScriptErrorReporter.report(
          "脚本 ${scriptFile.name} 未预编译，且未安装 klaymore-compiler.jar。" + "请使用开发版编译脚本，或将预编译产物放入缓存目录。")
      return null
    }

    // ⭐ 优先批量编译：把脚本所在目录的所有 .kt 一起编译，
    //   这样同目录内的脚本/类可以互相引用。
    val dir = scriptFile.parentFile
    if (dir != null) {
      val batchKey = dir.absolutePath
      if (batchDirLoaders.containsKey(batchKey) || loadBatchDirectory(dir, bridge)) {
        compileCache[absolutePath]?.let {
          lastModifiedCache[absolutePath] = lastModified
          return it
        }
      }
    }

    // 回退：单文件编译（脚本目录内无其他文件或批量编译不可用时）
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
        // ⭐ 修复：逐条展开 diagnostics，打印完整异常链（含 cause）
        // 之前只打印 it.toString()，导致 ExceptionInInitializerError 的真正原因全部丢失
        compileResult.reports
            .filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
            .forEach { diag ->
              // 1. 基础诊断文本
              System.err.println("[Klaymore Script] DIAGNOSTIC: ${diag.message}")
              // 2. 如果包含 ScriptDiagnostic.exception，打印完整堆栈
              try {
                val exField = diag.javaClass.getDeclaredField("exception")
                exField.isAccessible = true
                val ex = exField.get(diag) as? Throwable
                if (ex != null) {
                  var depth = 0
                  var cur: Throwable? = ex
                  while (cur != null && depth < 8) {
                    System.err.println(
                        "[Klaymore Script]   Cause chain [${depth}]: ${cur.javaClass.name}: ${cur.message}")
                    cur.stackTrace?.take(6)?.forEach { ste ->
                      System.err.println(
                          "[Klaymore Script]     at ${ste.className}.${ste.methodName}(${ste.fileName}:${ste.lineNumber})")
                    }
                    depth++
                    cur = cur.cause
                  }
                  // 最后完整打印一次最里层异常（便于复制完整堆栈）
                  if (depth > 0) {
                    System.err.println(
                        "[Klaymore Script]   --- Full stacktrace of original exception ---")
                    ex.printStackTrace(System.err)
                  }
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

  /**
   * 批量编译一个目录下的所有 .kt 脚本，并把每个脚本的 [KlaymoreScript] 子类缓存为 [CompiledScript]。
   *
   * 同目录内所有脚本共享一个 ClassLoader，因此脚本 A 定义的类可被脚本 B 直接引用。
   *
   * @return 批量编译成功且目录内至少有一个脚本被缓存 → true；否则 false。
   */
  private fun loadBatchDirectory(directory: File, bridge: ScriptCompilerBridge): Boolean {
    val batchKey = directory.absolutePath
    if (batchDirLoaders.containsKey(batchKey)) return true

    val result =
        try {
          bridge.compileBatch(directory)
        } catch (t: Throwable) {
          System.err.println("[Klaymore] compileBatch threw: ${t.message}")
          null
        }

    if (result == null || !result.success) {
      val msg = result?.errorMessage ?: "批量编译失败"
      System.err.println("[Klaymore] Batch compile failed for ${directory.name}: $msg")
      // 批量编译失败时不缓存失败状态，允许后续回退到单文件编译
      return false
    }

    // 用所有类字节码创建一个共享 ClassLoader（父加载器为 Launch.classLoader）
    val classLoader = BatchClassLoader(result.classBytes, Launch.classLoader)
    batchDirLoaders[batchKey] = classLoader

    // 扫描所有类，找到 KlaymoreScript 子类，并通过 SourceFile 属性映射回源文件
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

  /**
   * 从批量编译产物中找出所有 [KlaymoreScript] 子类，并通过 class 文件的 SourceFile 属性
   * 映射回源文件名。
   *
   * @return Map<源文件名, KlaymoreScript 子类的 KClass>
   */
  private fun findScriptClassesByFile(
      classBytes: Map<String, ByteArray>,
      classLoader: ClassLoader
  ): Map<String, kotlin.reflect.KClass<*>> {
    val result = LinkedHashMap<String, kotlin.reflect.KClass<*>>()
    val originalLoader = Thread.currentThread().contextClassLoader
    try {
      Thread.currentThread().contextClassLoader = classLoader

      for ((className, bytes) in classBytes) {
        // 跳过嵌套类（$ 结尾或包含 $ 的内部类），只看顶层类
        if (className.contains('$')) continue

        val clazz =
            try {
              classLoader.loadClass(className)
            } catch (_: Throwable) {
              continue
            }

        if (!KlaymoreScript::class.java.isAssignableFrom(clazz)) continue

        val sourceFile = parseSourceFileAttribute(bytes) ?: continue
        // 只保留文件名（去掉路径），因为编译时的 SourceFile 可能带目录
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

  /**
   * 解析 class 文件常量池，读取 SourceFile 属性（源文件名）。
   *
   * SourceFile 属性结构：attribute_name_index("SourceFile") + attribute_length(=2) + sourcefile_index(Utf8)。
   */
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

  /** 跳过 fields 或 methods 表（每个成员的结构相同：access_flags + name + descriptor + attributes）。 */
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

  @JvmStatic
  fun clearCache() {
    compileCache.clear()
    lastModifiedCache.clear()
    batchDirLoaders.clear()
  }

  /**
   * 根据脚本名称（如 "soldier.kts"）从缓存中获取已编译蓝图。
   *
   * 用于 [ScriptContainerFactory.spawnChild]：同一份脚本编译产物可被多个实例复用。 匹配策略：优先精确匹配绝对路径的文件名；其次匹配去掉扩展名的名称。
   */
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
    // 单文件失效时，同时清除其所在目录的批量编译缓存，
    // 确保下次加载时整个目录重新编译（脚本间引用关系可能已变化）。
    scriptFile.parentFile?.let { batchDirLoaders.remove(it.absolutePath) }
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
 * 同一目录下所有脚本编译出的类共享此加载器，因此脚本间可以互相引用彼此的类。
 * 父加载器为 Launch.classLoader，保证 Minecraft/Forge/Kotlin 类可见。
 */
private class BatchClassLoader(
    private val classes: Map<String, ByteArray>,
    parent: ClassLoader
) : ClassLoader(parent) {
  override fun findClass(name: String): Class<*> {
    val bytes = classes[name] ?: throw ClassNotFoundException(name)
    return defineClass(name, bytes, 0, bytes.size)
  }
}
