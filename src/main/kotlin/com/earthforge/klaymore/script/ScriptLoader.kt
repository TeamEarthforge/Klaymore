package com.earthforge.klaymore.script

import kotlinx.coroutines.runBlocking
import net.minecraft.launchwrapper.Launch
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

object ScriptLoader {
    private val compiler = JvmScriptCompiler()

    private val compileCache = mutableMapOf<String, CompiledScript>()
    private val lastModifiedCache = mutableMapOf<String, Long>()

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

        val compileResult = performCompile(scriptFile)
        return when (compileResult) {
            is ResultWithDiagnostics.Success -> {
                val compiled = compileResult.value
                compileCache[absolutePath] = compiled
                lastModifiedCache[absolutePath] = lastModified
                compiled
            }
            is ResultWithDiagnostics.Failure -> {
                val errors = compileResult.reports
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
            // 关键：切换上下文到 Launch.classLoader
            Thread.currentThread().contextClassLoader = Launch.classLoader

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
                println("=== Final classpath in config (${(cp as? Collection<*>)?.size ?: "unknown"} entries) ===")
                (cp as? Collection<*>)?.take(10)?.forEach { println("  - $it") }
            } catch (_: Exception) { /* ignore */ }

            runBlocking {
                compiler(FileScriptSource(scriptFile), config)
            }
        } finally {
            Thread.currentThread().contextClassLoader = originalClassLoader
        }
    }

    /**
     * 从 ClassLoader 中提取所有类路径（URL -> File）
     * 处理 URLClassLoader 和 LaunchClassLoader 的 sources 字段
     */
    private fun extractClasspathFromLoader(classLoader: ClassLoader): List<File> {
        val files = LinkedHashSet<File>()

        fun addUrl(url: URL) {
            try {
                when (url.protocol) {
                    "file" -> {
                        val file = Paths.get(url.toURI()).toFile()
                        if (file.exists()) files.add(file.absoluteFile)
                    }
                    "jar" -> {
                        val spec = url.file.substringBefore("!/")
                        val innerUrl = URL(spec)
                        addUrl(innerUrl)
                    }
                    else -> {
                        val file = File(url.path)
                        if (file.exists()) files.add(file.absoluteFile)
                    }
                }
            } catch (_: Exception) { /* ignore */ }
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
                    is List<*> -> sources.forEach { item ->
                        when (item) {
                            is URL -> addUrl(item)
                            is File -> if (item.exists()) files.add(item.absoluteFile)
                            is String -> File(item).takeIf { it.exists() }?.let { files.add(it.absoluteFile) }
                            else -> try { addUrl(URL(item.toString())) } catch (_: Exception) { /* ignore */ }
                        }
                    }
                    is Array<*> -> sources.forEach { item ->
                        when (item) {
                            is URL -> addUrl(item)
                            is File -> if (item.exists()) files.add(item.absoluteFile)
                            is String -> File(item).takeIf { it.exists() }?.let { files.add(it.absoluteFile) }
                            else -> try { addUrl(URL(item.toString())) } catch (_: Exception) { /* ignore */ }
                        }
                    }
                    else -> {
                        sources?.toString()?.let { File(it) }?.takeIf { it.exists() }?.let { files.add(it.absoluteFile) }
                    }
                }
            } catch (_: Exception) { /* ignore */ }
        }

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
    }
    private val compileExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
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
        // 检查缓存（同步返回）
        if (lastModifiedCache[path] == lastModified) {
            callback(compileCache[path])
            return
        }
        // 提交异步任务
        compileExecutor.submit {
            val compiled = performCompile(scriptFile)
            // 回到主线程执行回调
            net.minecraft.client.Minecraft.getMinecraft().func_152344_a {
                if (compiled is ResultWithDiagnostics.Success) {
                    val script = compiled.value
                    compileCache[path] = script
                    lastModifiedCache[path] = lastModified
                    callback(script)
                } else {
                    callback(null)
                }
            }
        }
    }
}
