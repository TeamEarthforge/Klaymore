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
import kotlin.script.experimental.host.FileScriptSource import kotlin.script.experimental.jvm.jvm
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

  @Volatile private var srgMinecraftJar: File? = null

  private fun srgMinecraftJar(): File {
    srgMinecraftJar?.let {
      if (it.exists()) return it
    }
    val cl = javaClass.classLoader
    val resource = cl.getResource("klaymore/minecraft-1.7.10-srg.jar")
        ?: throw IllegalStateException("SRG minecraft jar not found in classpath resources")
    val tmp = File.createTempFile("klaymore-mc-srg", ".jar")
    tmp.deleteOnExit()
    resource.openStream().use { input ->
      tmp.outputStream().use { output -> input.copyTo(output) }
    }
    println("[Klaymore] Extracted SRG minecraft dummy jar to: ${tmp.absolutePath}")
    srgMinecraftJar = tmp
    return tmp
  }

  private fun isMcOrForgeJar(file: File): Boolean {
    if (!file.exists() || !file.isFile || file.extension != "jar") return false
    return try {
      val zip = java.util.zip.ZipFile(file)
      var hasNotchClass = false
      var hasNetMinecraft = false
      var hasForge = false
      zip.use {
        for (entry in it.entries().asSequence()) {
          val name = entry.name
          if (name.endsWith(".class")) {
            if (name.indexOf('/') < 0) hasNotchClass = true
            if (name.startsWith("net/minecraft/")) hasNetMinecraft = true
            if (name.startsWith("cpw/mods/fml/") || name.startsWith("net/minecraftforge/")) hasForge = true
          }
          if ((hasNotchClass && hasNetMinecraft) || hasForge) break
        }
      }
      // 原版 Minecraft jar：同时含 notch 顶级类和 net/minecraft 类
      // Forge/FML jar：含 cpw.mods.fml 或 net.minecraftforge 类
      // 这两类 jar 里的 minecraft 引用都是 notch 命名，必须用 SRG dummy 替换
      (hasNotchClass && hasNetMinecraft) || hasForge
    } catch (_: Throwable) {
      false
    }
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
      val launchClasspath = extractClasspathFromLoader(launchCl)

      // 从加载编译器的 classloader 提取 Kotlin 运行时/脚本 API/编译器依赖
      // （klaymore-runtime-compiler.jar 内含 kotlin-compiler-embeddable 等）
      val compilerClasspath = extractClasspathFromLoader(javaClass.classLoader)

      // ⭐ 关键：把所有原版 minecraft jar 替换成 SRG 命名的 dummy jar。
      //   注意不能用 dependenciesFromCurrentContext(wholeClasspath=true)，
      //   因为那会把 LaunchClassLoader 里的 notch 命名 minecraft 类也拉进来，
      //   导致和 SRG 命名的类冲突、方法解析失败。
      val srgJar = srgMinecraftJar()
      val merged = LinkedHashSet<File>()
      val replaced = ArrayList<File>()
      for (f in compilerClasspath + launchClasspath) {
        if (isMcOrForgeJar(f)) {
          replaced.add(f)
        } else {
          merged.add(f.absoluteFile)
        }
      }
      merged.add(srgJar)
      val classpathFiles = merged.toList()
      println("=== Replaced ${replaced.size} minecraft jar(s) with SRG dummy ===")
      replaced.distinctBy { it.name }.forEach { println("  - removed: ${it.name}") }
      println("  + added:   ${srgJar.name}")
      println("=== Final classpath has ${classpathFiles.size} entries ===")
      classpathFiles.take(10).forEach { println("  - ${it.absolutePath}") }

      // 构建编译配置：只用手动构建的 classpath（全 SRG 命名 + Kotlin 依赖）
      val config = ScriptCompilationConfiguration {
        jvm {
          jvmTarget("1.8")
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

    // 4. 打印统计
    println("=== extractClasspathFromLoader: total ${files.size} entries ===")

    return files.toList()
  }
}
