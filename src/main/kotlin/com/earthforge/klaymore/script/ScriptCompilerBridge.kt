package com.earthforge.klaymore.script

import java.io.File
import java.util.ServiceLoader
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultWithDiagnostics

/**
 * 脚本编译器桥接接口。
 *
 * 主 mod（klaymore.jar）只依赖这个接口，不直接引用 Kotlin 编译器。 真正的编译实现放在独立的 klaymore-compiler.jar 里，通过
 * ServiceLoader 注入。
 *
 * 这样可以把产物拆成两部分：
 * - 玩家版：klaymore.jar + klaymore-runtime.jar（不含编译器，只能加载预编译产物）
 * - 开发版：klaymore.jar + klaymore-compiler.jar + klaymore-runtime.jar + klaymore-runtime-compiler.jar
 */
interface ScriptCompilerBridge {

  /** 同步编译脚本，返回编译结果（失败返回 null）。 */
  fun compile(scriptFile: File): ResultWithDiagnostics<CompiledScript>?

  /** 异步编译脚本，编译完成后在调用线程回调。 */
  fun compileAsync(scriptFile: File, callback: (ResultWithDiagnostics<CompiledScript>?) -> Unit)
}

/**
 * 全局编译器实例持有者。
 *
 * 启动时通过 ServiceLoader 查找 ScriptCompilerBridge 实现。 如果类路径上没有 klaymore-compiler.jar，[instance] 为 null，
 * ScriptLoader 会在缓存未命中时报错提示。
 */
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
