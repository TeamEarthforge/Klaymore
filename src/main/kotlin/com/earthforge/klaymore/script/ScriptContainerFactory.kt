package com.earthforge.klaymore.script

import java.io.File
import java.util.function.Consumer
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlinx.coroutines.runBlocking
import net.minecraft.launchwrapper.Launch

// ScriptContainerFactory.kt
object ScriptContainerFactory {

  @JvmStatic
  fun createAndMount(
      scriptName: String,
      scriptFile: File,
      target: Any,
      parentContainer: ScriptContainer? = null,
      initialPersistentData: Map<String, *>? = null
  ): ScriptContainer? {
    val compiled = ScriptLoader.loadScript(scriptFile) ?: return null
    return finishMount(compiled, scriptName, target, parentContainer, initialPersistentData)
  }

  /**
   * 异步版本：编译走后台线程，编译完成后回主线程完成实例化+注入+注册，最后通过 callback 返回
   * callback 永远在主线程调用，可以安全操作 Minecraft 世界 / 给玩家发消息等
   *
   * @param callback 接收挂载结果：成功 → ScriptContainer；失败 → null（编译错误或实例化错误）
   */
  @JvmStatic
  fun createAndMountAsync(
      scriptName: String,
      scriptFile: File,
      target: Any,
      parentContainer: ScriptContainer? = null,
      callback: Consumer<ScriptContainer?>
  ) {
    createAndMountAsync(scriptName, scriptFile, target, parentContainer, null) { container ->
      callback.accept(container)
    }
  }

  @JvmStatic
  fun createAndMountAsync(
      scriptName: String,
      scriptFile: File,
      target: Any,
      parentContainer: ScriptContainer?,
      initialPersistentData: Map<String, *>?,
      callback: Consumer<ScriptContainer?>
  ) {
    createAndMountAsync(scriptName, scriptFile, target, parentContainer, initialPersistentData) { container ->
      callback.accept(container)
    }
  }

  /** Kotlin 友好的重载：lambda */
  @JvmStatic
  fun createAndMountAsync(
      scriptName: String,
      scriptFile: File,
      target: Any,
      parentContainer: ScriptContainer? = null,
      initialPersistentData: Map<String, *>? = null,
      callback: (ScriptContainer?) -> Unit
  ) {
    ScriptLoader.loadScriptAsync(scriptFile) { compiled ->
      if (compiled == null) {
        callback(null)
        return@loadScriptAsync
      }
      val container = finishMount(compiled, scriptName, target, parentContainer, initialPersistentData)
      callback(container)
    }
  }

  /** 编译完成后的剩余挂载步骤（导入持久化数据 → 实例化 → 注入 → 订阅 → 注册管理器） */
  private fun finishMount(
      compiled: CompiledScript,
      scriptName: String,
      target: Any,
      parentContainer: ScriptContainer?,
      initialPersistentData: Map<String, *>?
  ): ScriptContainer? {
    val instance = instantiateScript(compiled, scriptName) ?: return null
    val effectiveTarget = resolveEffectiveTarget(instance, target, scriptName)
    val container =
        ScriptContainer(
            scriptName = scriptName,
            compiledScript = compiled,
            target = effectiveTarget,
            parent = parentContainer,
            scriptInstance = instance)

    parentContainer?.addChild(container)

    if (initialPersistentData != null) {
      try {
        container.importPersistentData(initialPersistentData)
      } catch (t: Throwable) {
        ScriptErrorReporter.report("导入持久化数据失败: ${t.message}")
      }
    }

    ScriptInjectionUtils.injectConventions(
        instance, effectiveTarget, parentContainer?.getTarget(), container)
    ScriptInjectionUtils.registerSubscribers(instance, effectiveTarget)

    ScriptBindingManager.register(container)

    return container
  }

  @JvmStatic
  fun unmount(container: ScriptContainer) {
    container.parent?.removeChild(container)
    container.children.toList().forEach { unmount(it) }
    container.onUnmount()
    ScriptBindingManager.unregister(container)
  }

  @JvmStatic
  fun unmountAll() {
    val all = ScriptBindingManager.getContainers().toList()
    for (container in all) {
      try {
        container.parent?.removeChild(container)
        container.children.toList().forEach { unmount(it) }
        container.onUnmount()
      } catch (t: Throwable) {
        // 忽略单个容器的清理错误，继续清理其他
      }
    }
    ScriptBindingManager.clearAll()
  }

  private fun resolveEffectiveTarget(
      scriptInstance: Any,
      providedTarget: Any,
      scriptName: String
  ): Any {
    if (providedTarget is Dummy) return providedTarget

    val wantsDummy =
        scriptInstance::class.java.declaredMethods.any { method ->
          method.name == "bindTarget" &&
              method.parameterCount == 1 &&
              method.parameterTypes[0] == Dummy::class.java
        }

    if (wantsDummy && providedTarget !is Dummy) {
      return Dummy("global_${scriptName.substringBeforeLast('.')}")
    }

    return providedTarget
  }

  private fun instantiateScript(compiledScript: CompiledScript, scriptName: String): Any? {
    val originalLoader = Thread.currentThread().contextClassLoader
    return try {
      Thread.currentThread().contextClassLoader = Launch.classLoader
      val evalConfig = ScriptEvaluationConfiguration {}
      val classResult = runBlocking { compiledScript.getClass(evalConfig) }
      when (classResult) {
        is ResultWithDiagnostics.Success -> {
          val kClass = classResult.value
          kClass.java.getDeclaredConstructor().newInstance()
        }
        is ResultWithDiagnostics.Failure -> {
          ScriptErrorReporter.report(
              "获取脚本类失败: ${classResult.reports.joinToString { it.toString() }}")
          null
        }
      }
    } catch (e: Exception) {
      ScriptErrorReporter.report("实例化脚本失败: ${e.message}")
      null
    } finally {
      Thread.currentThread().contextClassLoader = originalLoader
    }
  }

  @JvmStatic
  fun instantiateScriptForReload(
      compiledScript: CompiledScript,
      scriptName: String
  ): Any? = instantiateScript(compiledScript, scriptName)
}
