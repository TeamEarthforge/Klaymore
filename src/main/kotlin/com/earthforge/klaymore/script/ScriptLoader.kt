package com.earthforge.klaymore.script

import java.io.File
import java.util.function.Consumer
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

object ScriptLoader {

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
    // 未命中 → 尝试通过编译器桥接异步编译
    val bridge = ScriptCompilerHolder.instance
    if (bridge == null) {
      ScriptErrorReporter.report("脚本 ${scriptFile.name} 未预编译，且未安装 klaymore-compiler.jar。")
      MainThreadDispatcher.schedule(Runnable { callback(null) })
      return
    }

    bridge.compileAsync(scriptFile) { result ->
      when (result) {
        is ResultWithDiagnostics.Success -> {
          val script = result.value
          compileCache[path] = script
          lastModifiedCache[path] = lastModified
          ScriptClassCache.save(scriptFile, lastModified, script)
          MainThreadDispatcher.schedule(Runnable { callback(script) })
        }
        else -> MainThreadDispatcher.schedule(Runnable { callback(null) })
      }
    }
  }
}
