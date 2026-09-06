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
      initialPersistentData: Map<String, *>? = null,
      side: ScriptSide = ScriptSide.fromPath(scriptFile)
  ): ScriptContainer? {
    val compiled = ScriptLoader.loadScript(scriptFile) ?: return null
    return finishMount(compiled, scriptName, target, parentContainer, initialPersistentData, side)
  }

  /**
   * 异步版本：编译走后台线程，编译完成后回主线程完成实例化+注入+注册，最后通过 callback 返回 callback 永远在主线程调用，可以安全操作 Minecraft 世界 /
   * 给玩家发消息等
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
    createAndMountAsync(scriptName, scriptFile, target, parentContainer, initialPersistentData) {
        container ->
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
      side: ScriptSide = ScriptSide.fromPath(scriptFile),
      callback: (ScriptContainer?) -> Unit
  ) {
    ScriptLoader.loadScriptAsync(scriptFile) { compiled ->
      if (compiled == null) {
        callback(null)
        return@loadScriptAsync
      }
      val container =
          finishMount(compiled, scriptName, target, parentContainer, initialPersistentData, side)
      callback(container)
    }
  }

  /** 编译完成后的剩余挂载步骤（导入持久化数据 → 实例化 → 注入 → 订阅 → 注册管理器） */
  private fun finishMount(
      compiled: CompiledScript,
      scriptName: String,
      target: Any,
      parentContainer: ScriptContainer?,
      initialPersistentData: Map<String, *>?,
      side: ScriptSide
  ): ScriptContainer? {
    val instance = instantiateScript(compiled, scriptName) ?: return null
    val effectiveTarget = resolveEffectiveTarget(instance, target, scriptName)
    val container =
        ScriptContainer(
            scriptName = scriptName,
            compiledScript = compiled,
            target = effectiveTarget,
            parent = parentContainer,
            scriptInstance = instance,
            side = side)

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
    ScriptInjectionUtils.registerSubscribers(instance, effectiveTarget, side)

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
          val rawInstance = kClass.java.getDeclaredConstructor().newInstance()
          // 支持 `object Script { ... }` 等 Kotlin 单例写法：
          // 如果脚本实例自己没有 bindTarget / bindContainer 等约定方法，
          // 就遍历脚本类中所有静态 final INSTANCE 字段（Kotlin object 单例的特征），
          // 找到一个"看起来像脚本实现"的 object，拿它作为真正的脚本实例。
          unwrapScriptObject(rawInstance, scriptName)
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

  private val CONVENTION_METHOD_NAMES = setOf("bindTarget", "bindContainer", "bindParent")

  private fun looksLikeScriptImpl(obj: Any): Boolean {
    val methods = obj.javaClass.declaredMethods
    // 有任意约定方法 → 是脚本
    if (methods.any { it.name in CONVENTION_METHOD_NAMES }) return true
    // 有 @Subscribe 方法 → 是脚本
    return try {
      val subscribeAnno =
          Class.forName("com.earthforge.klaymore.script.Subscribe", true, Launch.classLoader)
      methods.any { m -> m.annotations.any { it.annotationClass.java == subscribeAnno } }
    } catch (_: Throwable) {
      false
    }
  }

  private fun unwrapScriptObject(rawInstance: Any, scriptName: String): Any {
    // 先看外壳本身是不是脚本实现
    if (looksLikeScriptImpl(rawInstance)) return rawInstance

    // 再扫描所有嵌套 Kotlin object 单例（静态 INSTANCE 字段）
    val rawClass = rawInstance.javaClass
    try {
      for (declaredClass in rawClass.declaredClasses) {
        try {
          val instanceField =
              try {
                declaredClass.getField("INSTANCE")
              } catch (_: NoSuchFieldException) {
                continue
              }
          val mods = instanceField.modifiers
          if (!java.lang.reflect.Modifier.isStatic(mods) ||
              !java.lang.reflect.Modifier.isFinal(mods))
              continue
          instanceField.isAccessible = true
          val obj = instanceField.get(null) ?: continue
          if (looksLikeScriptImpl(obj)) {
            println(
                "[Klaymore ScriptFactory] Detected Kotlin 'object' wrapper in " +
                    "$scriptName, using ${declaredClass.simpleName}.INSTANCE as actual script instance")
            return obj
          }
        } catch (_: Throwable) {
          // ignore this inner class, try next
        }
      }
    } catch (_: Throwable) {
      // ignore
    }

    // 什么都没找到，就返回原实例
    return rawInstance
  }

  @JvmStatic
  fun instantiateScriptForReload(compiledScript: CompiledScript, scriptName: String): Any? =
      instantiateScript(compiledScript, scriptName)
}
