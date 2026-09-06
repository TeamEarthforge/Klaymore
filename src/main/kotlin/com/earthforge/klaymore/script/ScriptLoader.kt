package com.earthforge.klaymore.script

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Paths
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Consumer
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.FileScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlinx.coroutines.runBlocking
import net.minecraft.launchwrapper.Launch

object ScriptLoader {

  private val compileCache = mutableMapOf<String, CompiledScript>()
  private val lastModifiedCache = mutableMapOf<String, Long>()

  private var compilerInitialized: Boolean = false
  private var compiler: JvmScriptCompiler? = null

  @Synchronized
  private fun getOrCreateCompiler(): JvmScriptCompiler {
    compiler?.let { return it }
    val savedCl = Thread.currentThread().contextClassLoader
    try {
      // ⭐ 关键：创建编译器前必须先切到 Launch.classLoader，
      // 否则 JvmScriptCompiler 内部类初始化（尤其是 FIR 前端的 FirFallbackBuiltinSymbolProvider）
      // 会用错误的 ClassLoader 找 kotlin 标准库/内置资源，导致 ExceptionInInitializerError，
      // 之后 JVM 会永久标记该类为初始化失败（"Could not initialize class" 错误）。
      Thread.currentThread().contextClassLoader = Launch.classLoader

      // 预热：手动强制加载几个关键类，让它们在正确的 classloader 上下文完成 <clinit>
      try {
        Class.forName(
            "org.jetbrains.kotlin.builtins.KotlinBuiltIns", true, Launch.classLoader)
      } catch (_: Throwable) { /* ignore */ }

      // 用正确的 ClassLoader 创建编译器实例
      val newCompiler =
          try {
            // 优先尝试带 ClassLoader 参数的构造（如果 kotlin-scripting-jvm-host 版本支持）
            val ctor = JvmScriptCompiler::class.java.getConstructor(ClassLoader::class.java)
            ctor.newInstance(Launch.classLoader)
          } catch (_: NoSuchMethodException) {
            // fallback: 无参构造（只要 contextClassLoader 已设置正确也能用）
            JvmScriptCompiler()
          }

      compiler = newCompiler
      compilerInitialized = true
      println("[Klaymore] ScriptLoader: JvmScriptCompiler initialized OK (classloader = Launch)")
      return newCompiler
    } catch (e: ExceptionInInitializerError) {
      // 捕获真正的根因（之前的错误日志只给出了缓存后的 NoClassDefFoundError，丢失了根异常堆栈）
      val cause = e.cause ?: e
      System.err.println(
          "[Klaymore] FATAL: JvmScriptCompiler class initialization FAILED (root cause captured)")
      cause.printStackTrace(System.err)
      throw RuntimeException(
          "Failed to initialize Kotlin script compiler (root cause: ${cause})", cause)
    } catch (t: Throwable) {
      System.err.println("[Klaymore] FATAL: Failed to create JvmScriptCompiler")
      t.printStackTrace(System.err)
      throw t
    } finally {
      Thread.currentThread().contextClassLoader = savedCl
    }
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

    val compileResult = performCompile(scriptFile)
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
                    System.err.println("[Klaymore Script]   Cause chain [${depth}]: ${cur.javaClass.name}: ${cur.message}")
                    cur.stackTrace?.take(6)?.forEach { ste ->
                      System.err.println("[Klaymore Script]     at ${ste.className}.${ste.methodName}(${ste.fileName}:${ste.lineNumber})")
                    }
                    depth++
                    cur = cur.cause
                  }
                  // 最后完整打印一次最里层异常（便于复制完整堆栈）
                  if (depth > 0) {
                    System.err.println("[Klaymore Script]   --- Full stacktrace of original exception ---")
                    ex.printStackTrace(System.err)
                  }
                }
              } catch (_: Throwable) { /* ignore */ }
            }
        val errors =
            compileResult.reports
                .filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
                .joinToString("\n") { it.toString() }
        ScriptErrorReporter.report("编译失败: ${scriptFile.name}\n$errors")
        null
      }
    }
  }

  private fun performCompile(scriptFile: File): ResultWithDiagnostics<CompiledScript> {
    val originalClassLoader = Thread.currentThread().contextClassLoader
    return try {
      // 关键：切换上下文到 Launch.classLoader（编译器初始化、类/资源加载全依赖这个）
      Thread.currentThread().contextClassLoader = Launch.classLoader

      // 在正确的 classloader 上下文下惰性创建编译器（首次调用时创建）
      val compilerInstance = getOrCreateCompiler()

      // 手动提取所有类路径（包含 Forge/Minecraft 所有 JAR）
      val classpathFiles = extractClasspathFromLoader(Launch.classLoader)
      println("=== Extracted ${classpathFiles.size} classpath entries from Launch.classLoader ===")
      classpathFiles.take(10).forEach { println("  - ${it.absolutePath}") }

      // 构建编译配置：同时使用自动依赖和手动追加
      val config = ScriptCompilationConfiguration {
        jvm {
          jvmTarget("1.8")
          // 1. 自动从当前上下文加载器获取依赖（包含 Kotlin 运行时、脚本 API 等）
          dependenciesFromCurrentContext(wholeClasspath = true)
          // 2. 手动追加我们提取的所有类路径（确保 Forge/Minecraft 类可见）
          updateClasspath(classpathFiles)
        }
      }

      // 调试：打印配置中的最终类路径（反射获取）
      try {
        val cpField = config.javaClass.getDeclaredField("classpath")
        cpField.isAccessible = true
        val cp = cpField.get(config)
        println(
            "=== Final classpath in config (${(cp as? Collection<*>)?.size ?: "unknown"} entries) ===")
        (cp as? Collection<*>)?.take(10)?.forEach { println("  - $it") }
      } catch (_: Exception) {
        /* ignore */
      }

      println("[Klaymore] Compiling script: ${scriptFile.absolutePath}")
      runBlocking { compilerInstance(FileScriptSource(scriptFile), config) }
    } catch (e: Throwable) {
      // 补充上下文到异常信息，便于排查
      System.err.println(
          "[Klaymore] ScriptLoader.performCompile EXCEPTION for ${scriptFile.name}: ${e.message}")
      e.printStackTrace(System.err)
      throw e
    } finally {
      Thread.currentThread().contextClassLoader = originalClassLoader
    }
  }

  /** 从 ClassLoader 中提取所有类路径（URL -> File） 处理 URLClassLoader 和 LaunchClassLoader 的 sources 字段 */
  private fun extractClasspathFromLoader(classLoader: ClassLoader): List<File> {
    val files = LinkedHashSet<File>()

    fun addFile(f: File) {
      if (f.exists()) files.add(f.absoluteFile)
    }

    fun addUrl(url: URL) {
      try {
        when (url.protocol) {
          "file" -> {
            val file = Paths.get(url.toURI()).toFile()
            addFile(file)
          }
          "jar" -> {
            val spec = url.file.substringBefore("!/")
            val innerUrl = URL(spec)
            addUrl(innerUrl)
          }
          else -> {
            val file = File(url.path)
            addFile(file)
          }
        }
      } catch (_: Exception) {
        /* ignore */
      }
    }

    // 1. 如果是 URLClassLoader
    if (classLoader is URLClassLoader) {
      classLoader.urLs.forEach(::addUrl)
    }

    // 2. 尝试反射 getURLs()（适用于 LaunchClassLoader）
    try {
      val method = classLoader.javaClass.getMethod("getURLs")
      val urls = method.invoke(classLoader) as? Array<URL>
      urls?.forEach(::addUrl)
    } catch (_: Exception) {
      // 3. 尝试反射 sources 字段（Forge 特有）
      try {
        val field = classLoader.javaClass.getDeclaredField("sources")
        field.isAccessible = true
        val sources = field.get(classLoader)
        when (sources) {
          is List<*> ->
              sources.forEach { item ->
                when (item) {
                  is URL -> addUrl(item)
                  is File -> addFile(item)
                  is String -> addFile(File(item))
                  else ->
                      try {
                        addUrl(URL(item.toString()))
                      } catch (_: Exception) {
                        /* ignore */
                      }
                }
              }
          is Array<*> ->
              sources.forEach { item ->
                when (item) {
                  is URL -> addUrl(item)
                  is File -> addFile(item)
                  is String -> addFile(File(item))
                  else ->
                      try {
                        addUrl(URL(item.toString()))
                      } catch (_: Exception) {
                        /* ignore */
                      }
                }
              }
          else -> {
            sources?.toString()?.let { addFile(File(it)) }
          }
        }
      } catch (_: Exception) {
        /* ignore */
      }
    }

    // ⭐⭐⭐ 4. 额外追加：Forge 开发环境 classes 输出目录
    // （Forge 1.7.10 的 LaunchClassLoader 在开发模式下不把 build/classes/java/* 目录放入 sources/getURLs，
    //  但运行时能通过 transformer wrapper 找到。 kotlin 编译器直接查 classpath，所以找不到）
    // 4a. 首先根据已识别的 recompiled_minecraft-1.7.10.jar 反推出各项目根目录
    val candidateProjectRoots = LinkedHashSet<File>()
    for (f in files) {
      val name = f.name
      if (name == "recompiled_minecraft-1.7.10.jar" || name == "mclauncher-1.7.10.jar") {
        // F:\MCRPG\CustomNpc-Plus-Klaymore\build\rfg\recompiled_minecraft-1.7.10.jar
        // 向上 2 级 → 项目根
        f.parentFile?.parentFile?.parentFile?.let { candidateProjectRoots.add(it) }
      }
      if (f.path.endsWith("build/libs/klaymore-runtime.jar")) {
        f.parentFile?.parentFile?.parentFile?.let { candidateProjectRoots.add(it) }
      }
    }
    // 4b. 兜底：把当前 System.getProperty("user.dir") 的父级目录也作为候选（多项目 workspace 常见结构）
    try {
      val cwd = File(System.getProperty("user.dir"))
      candidateProjectRoots.add(cwd)
      cwd.parentFile?.let { candidateProjectRoots.add(it) }
    } catch (_: Throwable) { /* ignore */ }

    fun addBuildClassesDirs(projectDir: File) {
      if (!projectDir.isDirectory) return
      val buildClassesJava = File(projectDir, "build/classes/java")
      if (buildClassesJava.isDirectory) {
        // 通常是 main / api / patchedMc / injectedTags / mcLauncher 等 source-set 输出
        buildClassesJava.listFiles()?.forEach { sourceSetDir ->
          if (sourceSetDir.isDirectory) {
            addFile(sourceSetDir)
          }
        }
      }
      // 有些项目直接把 classes 输出到 build/classes
      val buildClasses = File(projectDir, "build/classes")
      if (buildClasses.isDirectory) addFile(buildClasses)
      // kotlin 独立输出目录
      val buildClassesKotlin = File(projectDir, "build/classes/kotlin")
      if (buildClassesKotlin.isDirectory) {
        buildClassesKotlin.listFiles()?.forEach { ss ->
          if (ss.isDirectory) addFile(ss)
        }
      }
      // 项目 libs/ 目录（比如 Klaymore/libs/klaymore-runtime.jar）
      val libsDir = File(projectDir, "libs")
      if (libsDir.isDirectory) {
        libsDir.listFiles()?.forEach { jar ->
          if (jar.isFile && jar.extension.equals("jar", ignoreCase = true)) {
            addFile(jar)
          }
        }
      }
      // run/mods 目录（开发模式下经常放其他依赖 mod 的 jar）
      val runModsDirs =
          listOf(
              File(projectDir, "run/client/mods"),
              File(projectDir, "run/server/mods"),
              File(projectDir, "run/mods"))
      for (modsDir in runModsDirs) {
        if (modsDir.isDirectory) {
          modsDir.listFiles()?.forEach { jar ->
            if (jar.isFile && jar.extension.equals("jar", ignoreCase = true)) {
              addFile(jar)
            }
          }
        }
      }
    }

    for (root in candidateProjectRoots) {
      addBuildClassesDirs(root)
      // 还会扫描根目录下的所有子目录（识别 F:\MCRPG\Klaymore + F:\MCRPG\CustomNpc-Plus-Klaymore 这种兄弟项目）
      root.listFiles()?.forEach { sibling ->
        if (sibling.isDirectory) {
          addBuildClassesDirs(sibling)
        }
      }
    }

    // 4c. 打印统计 + CustomNPCs 类的命中情况（开发调试用）
    var noppesClassesDir: File? = null
    for (f in files) {
      if (f.isDirectory && File(f, "noppes/npcs/entity/EntityNPCInterface.class").exists()) {
        noppesClassesDir = f
        break
      }
    }
    println(
        "=== extractClasspathFromLoader: total ${files.size} entries, " +
            "CustomNPCs-found=${noppesClassesDir != null} ===")
    if (noppesClassesDir != null) {
      println("    + CustomNPCs classes dir: ${noppesClassesDir.absolutePath}")
    } else {
      println("    ⚠ CustomNPCs classes dir NOT FOUND in compile classpath!")
    }
    // 额外确认关键 API class 是否存在
    val hasICustomNpc = files.any { f ->
      File(f, "noppes/npcs/api/entity/ICustomNpc.class").exists()
    }
    val hasEntityCustomNpc = files.any { f ->
      File(f, "noppes/npcs/entity/EntityCustomNpc.class").exists()
    }
    println("    + ICustomNpc.class: $hasICustomNpc, EntityCustomNpc.class: $hasEntityCustomNpc")

    return files.toList()
  }

  @JvmStatic
  fun loadDirectory(directory: File, extension: String = "kts"): Int {
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
  }

  @JvmStatic
  fun invalidateCache(scriptFile: File) {
    val path = scriptFile.absolutePath
    compileCache.remove(path)
    lastModifiedCache.remove(path)
    // 同时清掉磁盘缓存，确保下次加载重新编译（用于 /klaymore reload 等场景）
    ScriptClassCache.invalidate(scriptFile)
  }

  private val compileExecutor: ExecutorService =
      Executors.newSingleThreadExecutor { r ->
        Thread(r, "Klaymore-Compiler").apply { isDaemon = true }
      }

  @JvmStatic
  fun loadScriptAsync(scriptFile: File, callback: Consumer<CompiledScript?>) {
    loadScriptAsync(scriptFile) { compiled -> callback.accept(compiled) }
  }

  @JvmStatic
  fun loadScriptAsync(scriptFile: File, callback: (CompiledScript?) -> Unit) {
    val path = scriptFile.absolutePath
    val lastModified = scriptFile.lastModified()
    // 缓存命中 → 统一通过 MainThreadDispatcher 回调（如果已经在主线程，会直接运行不排队）
    if (lastModifiedCache[path] == lastModified) {
      MainThreadDispatcher.schedule(Runnable { callback(compileCache[path]) })
      return
    }
    // 内存未命中 → 尝试磁盘缓存（命中则直接回调，无需走编译线程）
    val cached = ScriptClassCache.load(scriptFile, lastModified)
    if (cached != null) {
      compileCache[path] = cached
      lastModifiedCache[path] = lastModified
      MainThreadDispatcher.schedule(Runnable { callback(cached) })
      return
    }
    // 未命中 → 提交到后台编译线程
    compileExecutor.submit {
      try {
        val compiled = performCompile(scriptFile)
        // 编译完成 → 回到主线程更新缓存 + 回调
        MainThreadDispatcher.schedule(Runnable {
          if (compiled is ResultWithDiagnostics.Success<*>) {
            @Suppress("UNCHECKED_CAST")
            val script = (compiled as ResultWithDiagnostics.Success<CompiledScript>).value
            compileCache[path] = script
            lastModifiedCache[path] = lastModified
            // 落盘缓存（失败不影响本次使用）
            ScriptClassCache.save(scriptFile, lastModified, script)
            callback(script)
          } else {
            callback(null)
          }
        })
      } catch (t: Throwable) {
        System.err.println("[Klaymore ScriptLoader] Uncaught exception during async compile of "
            + scriptFile.name + ": " + t.message)
        t.printStackTrace(System.err)
        MainThreadDispatcher.schedule(Runnable { callback(null) })
      }
    }
  }
}
