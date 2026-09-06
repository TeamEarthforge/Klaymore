package com.earthforge.klaymore.script

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Paths
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.host.FileScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlinx.coroutines.runBlocking

/**
 * Kotlin 脚本编译器实现。
 *
 * 放在独立的 klaymore-compiler.jar 里，通过 ServiceLoader 注入到主 mod。
 * 玩家版不包含此 jar，因此无法编译脚本，只能加载预编译产物。
 */
class ScriptCompiler : ScriptCompilerBridge {

  @Volatile private var compilerInitialized: Boolean = false
  @Volatile private var compiler: JvmScriptCompiler? = null

  private val compileExecutor: ExecutorService =
      Executors.newSingleThreadExecutor { r ->
        Thread(r, "Klaymore-Compiler").apply { isDaemon = true }
      }

  /** 通过反射获取 Forge 的 Launch.classLoader，避免模块直接依赖 Forge。 */
  private fun launchClassLoader(): ClassLoader {
    return try {
      val launchClass = Class.forName("net.minecraft.launchwrapper.Launch")
      val field = launchClass.getField("classLoader")
      field.get(null) as ClassLoader
    } catch (t: Throwable) {
      throw RuntimeException("Cannot find net.minecraft.launchwrapper.Launch.classLoader", t)
    }
  }

  @Synchronized
  private fun getOrCreateCompiler(): JvmScriptCompiler {
    compiler?.let {
      return it
    }
    val savedCl = Thread.currentThread().contextClassLoader
    try {
      // ⭐ 关键：创建编译器前必须先切到 Launch.classLoader，
      // 否则 JvmScriptCompiler 内部类初始化（尤其是 FIR 前端的 FirFallbackBuiltinSymbolProvider）
      // 会用错误的 ClassLoader 找 kotlin 标准库/内置资源，导致 ExceptionInInitializerError，
      // 之后 JVM 会永久标记该类为初始化失败（"Could not initialize class" 错误）。
      val launchCl = launchClassLoader()
      Thread.currentThread().contextClassLoader = launchCl

      // 预热：手动强制加载几个关键类，让它们在正确的 classloader 上下文完成 <clinit>
      try {
        Class.forName("org.jetbrains.kotlin.builtins.KotlinBuiltIns", true, launchCl)
      } catch (_: Throwable) {
        /* ignore */
      }

      // 用正确的 ClassLoader 创建编译器实例
      val newCompiler =
          try {
            // 优先尝试带 ClassLoader 参数的构造（如果 kotlin-scripting-jvm-host 版本支持）
            val ctor = JvmScriptCompiler::class.java.getConstructor(ClassLoader::class.java)
            ctor.newInstance(launchCl)
          } catch (_: NoSuchMethodException) {
            // fallback: 无参构造（只要 contextClassLoader 已设置正确也能用）
            JvmScriptCompiler()
          }

      compiler = newCompiler
      compilerInitialized = true
      println("[Klaymore] ScriptCompiler: JvmScriptCompiler initialized OK (classloader = Launch)")
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

  override fun compile(scriptFile: File): ResultWithDiagnostics<CompiledScript>? {
    val originalClassLoader = Thread.currentThread().contextClassLoader
    return try {
      // 关键：切换上下文到 Launch.classLoader（编译器初始化、类/资源加载全依赖这个）
      val launchCl = launchClassLoader()
      Thread.currentThread().contextClassLoader = launchCl

      // 在正确的 classloader 上下文下惰性创建编译器（首次调用时创建）
      val compilerInstance = getOrCreateCompiler()

      // 手动提取所有类路径（包含 Forge/Minecraft 所有 JAR）
      val classpathFiles = extractClasspathFromLoader(launchCl)
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
          "[Klaymore] ScriptCompiler.compile EXCEPTION for ${scriptFile.name}: ${e.message}")
      e.printStackTrace(System.err)
      null
    } finally {
      Thread.currentThread().contextClassLoader = originalClassLoader
    }
  }

  override fun compileAsync(
      scriptFile: File,
      callback: (ResultWithDiagnostics<CompiledScript>?) -> Unit
  ) {
    compileExecutor.submit {
      try {
        val result = compile(scriptFile)
        callback(result)
      } catch (t: Throwable) {
        System.err.println(
            "[Klaymore ScriptCompiler] Uncaught exception during async compile of " +
                scriptFile.name +
                ": " +
                t.message)
        t.printStackTrace(System.err)
        callback(null)
      }
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
    } catch (_: Throwable) {
      /* ignore */
    }

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
        buildClassesKotlin.listFiles()?.forEach { ss -> if (ss.isDirectory) addFile(ss) }
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
    val hasICustomNpc =
        files.any { f -> File(f, "noppes/npcs/api/entity/ICustomNpc.class").exists() }
    val hasEntityCustomNpc =
        files.any { f -> File(f, "noppes/npcs/entity/EntityCustomNpc.class").exists() }
    println("    + ICustomNpc.class: $hasICustomNpc, EntityCustomNpc.class: $hasEntityCustomNpc")

    return files.toList()
  }
}
