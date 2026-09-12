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

  /** 已批量编译的目录 → 该目录所有类共享的 ClassLoader（同目录脚本可互相引用）。 */
  private val batchDirLoaders = mutableMapOf<String, ClassLoader>()

  /**
   * common/ 目录编译产物的共享 ClassLoader。
   *
   * 所有 server/ 和 client/ 的 ClassLoader 都以此为父加载器， 因此 common/ 里定义的类在 JVM 中只存在一份，跨目录可直接引用、可互相转型。
   */
  @Volatile private var commonClassLoader: ClassLoader? = null

  /**
   * common/ 编译输出目录（持久化到 cache/common-classes/）， 编译 server/client 脚本时把此目录加到 classpath，让编译器能解析 common
   * 类。
   */
  @Volatile private var commonClasspathDir: File? = null

  /** common/ 目录是否已尝试加载过（避免重复编译）。 */
  @Volatile private var commonLoaded: Boolean = false

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
        // 该文件属于已成功批量编译的目录，但没有定义 KlaymoreScript 子类
        // （例如只定义 data class / object / 普通工具类）。
        // 不回退到单文件编译——单文件编译会因无法解析同目录其他脚本的引用而失败。
        // 标记为已处理，避免重复编译；返回 null 表示"无脚本类可挂载"。
        lastModifiedCache[absolutePath] = lastModified
        return null
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
   * 若 common/ 目录存在且已编译，当前目录的 ClassLoader 会以 commonClassLoader 为父加载器， 这样当前目录的脚本可以直接引用 common/ 里定义的类。
   *
   * @return 批量编译成功且目录内至少有一个脚本被缓存 → true；否则 false。
   */
  private fun loadBatchDirectory(directory: File, bridge: ScriptCompilerBridge): Boolean {
    val batchKey = directory.absolutePath
    if (batchDirLoaders.containsKey(batchKey)) return true

    // 确保 common/ 已加载（作为 classpath 依赖 + 父加载器）
    ensureCommonLoaded(bridge)

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
      // 批量编译失败时不缓存失败状态，允许后续回退到单文件编译
      return false
    }

    // 用所有类字节码创建共享 ClassLoader，父加载器优先用 commonClassLoader（让 common 类共享），
    // 否则回退到 Launch.classLoader。
    val parent = commonClassLoader ?: Launch.classLoader
    val classLoader = BatchClassLoader(result.classBytes, parent)
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

  // ================================================================================
  //  common/ 目录加载与注册阶段
  // ================================================================================

  /**
   * 确保 common/ 目录已编译并加载到 [commonClassLoader]。
   *
   * 幂等：多次调用只编译一次。
   */
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

    // 把 common 类字节码写到持久化目录，供 server/client 编译时作为 classpath
    val outDir = File(MinecraftDirectory.getCacheDirectory(), "common-classes")
    if (outDir.exists()) outDir.deleteRecursively()
    outDir.mkdirs()
    for ((className, bytes) in result.classBytes) {
      val classFile = File(outDir, className.replace('.', File.separatorChar) + ".class")
      classFile.parentFile?.mkdirs()
      classFile.writeBytes(bytes)
    }
    commonClasspathDir = outDir

    // 创建共享 ClassLoader，父加载器为 Launch.classLoader
    commonClassLoader = BatchClassLoader(result.classBytes, Launch.classLoader)

    println(
        "[Klaymore] Common scripts loaded: ${result.classBytes.size} classes -> shared ClassLoader ready")
  }

  /**
   * 编译 common/ 目录，实例化其中所有 [KlaymoreScript] 子类并调用 [KlaymoreScript.onRegister]。
   *
   * 在 preInit 阶段调用，用于完成物品/方块等早期注册。
   *
   * @return 成功执行 onRegister 的脚本数量
   */
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

    // 重新拿 common 的 classBytes（从 commonClasspathDir 读），找到 KlaymoreScript 子类
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

  /**
   * 从批量编译产物中找出所有 [KlaymoreScript] 子类，并通过 class 文件的 SourceFile 属性 映射回源文件名。
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
   * SourceFile 属性结构：attribute_name_index("SourceFile") + attribute_length(=2) +
   * sourcefile_index(Utf8)。
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

  /**
   * 判断脚本文件是否已被处理（批量编译成功并缓存，或已标记为无 KlaymoreScript 子类）。
   *
   * 用于区分"批量编译成功但该文件不定义脚本类"和"编译失败"两种情况：
   * - 返回 true：文件已成功处理（要么有 CompiledScript 缓存，要么属于已批量编译的目录但无脚本类）
   * - 返回 false：文件未处理或编译失败
   */
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
    } else {
      // 单文件失效时，同时清除其所在目录的批量编译缓存，
      // 确保下次加载时整个目录重新编译（脚本间引用关系可能已变化）。
      scriptFile.parentFile?.let { batchDirLoaders.remove(it.absolutePath) }
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
