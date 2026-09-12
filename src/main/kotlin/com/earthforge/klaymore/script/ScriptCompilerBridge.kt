package com.earthforge.klaymore.script

import java.io.File
import java.util.ServiceLoader
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultWithDiagnostics

/** 脚本编译器桥接接口。主 mod 只依赖此接口，编译实现在 klaymore-compiler.jar 中通过 ServiceLoader 注入。 */
interface ScriptCompilerBridge {

  /** 同步编译脚本，返回编译结果（失败返回 null）。 */
  fun compile(scriptFile: File): ResultWithDiagnostics<CompiledScript>?

  /** 异步编译脚本，编译完成后在调用线程回调。 */
  fun compileAsync(scriptFile: File, callback: (ResultWithDiagnostics<CompiledScript>?) -> Unit)

  /** 批量编译目录下所有 .kt 脚本，同目录脚本可互相引用 */
  fun compileBatch(directory: File): BatchCompileResult?

  /** 批量编译目录下所有 .kt 脚本，支持额外 classpath（如引用 common/ 已编译类） */
  fun compileBatch(directory: File, extraClasspath: List<File>): BatchCompileResult? =
      compileBatch(directory)
}

/** 批量编译结果 */
data class BatchCompileResult(
    val classBytes: Map<String, ByteArray>,
    val success: Boolean,
    val errorMessage: String?
)

/** 全局编译器实例持有者，通过 ServiceLoader 查找实现 */
object ScriptCompilerHolder {

  @Volatile
  var instance: ScriptCompilerBridge? = null
    private set

  init {
    try {
      val loader = ServiceLoader.load(ScriptCompilerBridge::class.java)
      val first = loader.firstOrNull()
      if (first != null) {
        instance = first
        println("[Klaymore] ScriptCompilerBridge loaded: ${first.javaClass.name}")
      } else {
        println(
            "[Klaymore] ScriptCompilerBridge not found (klaymore-compiler.jar not present). " +
                "Scripts must be pre-compiled.")
      }
    } catch (t: Throwable) {
      System.err.println("[Klaymore] Failed to load ScriptCompilerBridge: ${t.message}")
    }
  }
}
