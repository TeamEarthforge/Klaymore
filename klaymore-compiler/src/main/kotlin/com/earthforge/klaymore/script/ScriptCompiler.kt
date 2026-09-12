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
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

/** Kotlin 脚本编译器实现，通过 ServiceLoader 注入到主 mod */
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
    private fun clearPollutedEventPackage() {
        try {
            val cl = launchClassLoader()

            // 1. 清 package2certs 里 cpw.mods.fml.common.event 的记录
            val certsField = ClassLoader::class.java.getDeclaredField("package2certs")
            certsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val certs = certsField.get(cl) as MutableMap<String, Any?>
            val removedCerts = certs.keys.filter { it.startsWith("cpw.mods.fml.") || it.startsWith("net.minecraftforge.") }
            removedCerts.forEach { certs.remove(it) }
            if (removedCerts.isNotEmpty()) {
                println("[Klaymore] cleared package2certs keys: $removedCerts")
            }

            // 2. 清 invalidClasses
            val cf = Class.forName("net.minecraft.launchwrapper.LaunchClassLoader")
            val invalidField = cf.getDeclaredField("invalidClasses")
            invalidField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val invalid = invalidField.get(cl) as MutableSet<String>
            val removedInvalid = invalid.filter { it.startsWith("cpw.mods.fml.") || it.startsWith("net.minecraftforge.") }
            removedInvalid.forEach { invalid.remove(it) }
            if (removedInvalid.isNotEmpty()) {
                println("[Klaymore] cleared invalidClasses: $removedInvalid")
            }
        } catch (t: Throwable) {
            System.err.println("[Klaymore] clearPollutedEventPackage failed: ${t.message}")
            t.printStackTrace(System.err)
        }
    }
  @Synchronized
  private fun getOrCreateCompiler(): JvmScriptCompiler {
    compiler?.let {
      return it
    }
    val savedCl = Thread.currentThread().contextClassLoader
    try {
      val launchCl = launchClassLoader()
      Thread.currentThread().contextClassLoader = launchCl

      try {
        Class.forName("org.jetbrains.kotlin.builtins.KotlinBuiltIns", true, launchCl)
      } catch (_: Throwable) {
      }

      val newCompiler =
          try {
            val ctor = JvmScriptCompiler::class.java.getConstructor(ClassLoader::class.java)
            ctor.newInstance(launchCl)
          } catch (_: NoSuchMethodException) {
            JvmScriptCompiler()
          }

      compiler = newCompiler
      compilerInitialized = true
      println("[Klaymore] ScriptCompiler: JvmScriptCompiler initialized OK (classloader = Launch)")
      return newCompiler
    } catch (e: ExceptionInInitializerError) {
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
      val launchCl = launchClassLoader()
      Thread.currentThread().contextClassLoader = launchCl

      val compilerInstance = getOrCreateCompiler()
      val classpathFiles = buildCompilationClasspath()

      val config = ScriptCompilationConfiguration {
        jvm {
          jvmTarget("1.8")
          updateClasspath(classpathFiles)
        }
      }

      println("[Klaymore] Compiling script: ${scriptFile.absolutePath}")
      runBlocking { compilerInstance(FileScriptSource(scriptFile), config) }
    } catch (e: Throwable) {
      System.err.println(
          "[Klaymore] ScriptCompiler.compile EXCEPTION for ${scriptFile.name}: ${e.message}")
      e.printStackTrace(System.err)
      null
    } finally {
      Thread.currentThread().contextClassLoader = originalClassLoader
    }
  }

  /** 批量编译目录下所有 .kt 脚本，同目录脚本可互相引用 */
  override fun compileBatch(directory: File): BatchCompileResult? =
      compileBatch(directory, emptyList())

  /** 批量编译，支持额外 classpath */
  override fun compileBatch(directory: File, extraClasspath: List<File>): BatchCompileResult? {
    val originalClassLoader = Thread.currentThread().contextClassLoader
    return try {
      val launchCl = launchClassLoader()
      Thread.currentThread().contextClassLoader = launchCl

      val ktFiles =
          directory.listFiles { f -> f.isFile && f.extension.equals("kt", ignoreCase = true) }
              ?: return BatchCompileResult(emptyMap(), true, null)
      if (ktFiles.isEmpty()) return BatchCompileResult(emptyMap(), true, null)

      val classpathFiles = buildCompilationClasspath() + extraClasspath.filter { it.exists() }
      val classpathStr = classpathFiles.joinToString(File.pathSeparator) { it.absolutePath }

      val outputDir = createTempDir("klaymore-batch-out")
      outputDir.deleteOnExit()

      val args =
          arrayOf(
              "-no-stdlib",
              "-no-reflect",
              "-classpath",
              classpathStr,
              "-d",
              outputDir.absolutePath,
              "-jvm-target",
              "1.8",
              *ktFiles.map { it.absolutePath }.toTypedArray())

      println(
          "[Klaymore] Batch compiling ${ktFiles.size} scripts in ${directory.absolutePath}" +
              (if (extraClasspath.isNotEmpty()) " (with ${extraClasspath.size} extra classpath entries)" else ""))

      val compiler = K2JVMCompiler()
      val errStream = java.io.ByteArrayOutputStream()
      val exitCode = compiler.exec(java.io.PrintStream(errStream, true), *args)

      if (exitCode != ExitCode.OK) {
        val msg = errStream.toString(Charsets.UTF_8.name())
        System.err.println("[Klaymore] Batch compile FAILED for ${directory.name}:")
        System.err.println(msg)
        return BatchCompileResult(emptyMap(), false, msg)
      }

      val classBytes = LinkedHashMap<String, ByteArray>()
      outputDir.walkTopDown().forEach { f ->
        if (f.isFile && f.extension.equals("class", ignoreCase = true)) {
          val relative = f.relativeTo(outputDir).path
          val className =
              relative.removeSuffix(".class").replace(File.separatorChar, '.').replace('/', '.')
          classBytes[className] = f.readBytes()
        }
      }

      println(
          "[Klaymore] Batch compile OK: ${classBytes.size} classes from ${directory.name}")
      BatchCompileResult(classBytes, true, null)
    } catch (e: Throwable) {
      System.err.println(
          "[Klaymore] ScriptCompiler.compileBatch EXCEPTION for ${directory.name}: ${e.message}")
      e.printStackTrace(System.err)
      BatchCompileResult(emptyMap(), false, e.message)
    } finally {
        clearPollutedEventPackage()
      Thread.currentThread().contextClassLoader = originalClassLoader
    }
  }

  /** 构建编译用 classpath，将 notch 命名 jar 替换为 SRG dummy jar。 */
  private fun buildCompilationClasspath(): List<File> {
    val launchCl = launchClassLoader()
    val launchClasspath = extractClasspathFromLoader(launchCl)
    val compilerClasspath = extractClasspathFromLoader(javaClass.classLoader)

    val srgJar = srgMinecraftJar()
    val merged = LinkedHashSet<File>()
    for (f in compilerClasspath + launchClasspath) {
      if (!isMcOrForgeJar(f)) {
        merged.add(f.absoluteFile)
      }
    }
    merged.add(srgJar)
    return merged.toList()
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

  /** 从 ClassLoader 中提取所有类路径 */
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

    if (classLoader is URLClassLoader) {
      classLoader.urLs.forEach(::addUrl)
    }

    try {
      val method = classLoader.javaClass.getMethod("getURLs")
      val urls = method.invoke(classLoader) as? Array<URL>
      urls?.forEach(::addUrl)
    } catch (_: Exception) {
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
