package com.earthforge.klaymore.script

import java.io.File
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
      parentContainer: ScriptContainer? = null
  ): ScriptContainer? {
    val compiled = ScriptLoader.loadScript(scriptFile) ?: return null

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
