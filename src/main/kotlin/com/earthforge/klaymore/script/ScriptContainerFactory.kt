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
        // 导入后从持久化数据中提取路径（klaymore.path）并归一化
        container.syncPathFromPersistentData()
      } catch (t: Throwable) {
        ScriptErrorReporter.report("导入持久化数据失败: ${t.message}")
      }
    }

    ScriptInjectionUtils.injectConventions(
        instance, effectiveTarget, parentContainer?.getTarget(), container)
    ScriptInjectionUtils.registerSubscribers(instance, effectiveTarget, side)

    // 注册到 ContainerIndex（Key -> Container），实现 O(1) 查找
    val key = PersistenceManager.generateKey(effectiveTarget)
    if (!key.isNullOrEmpty()) {
      ContainerIndex.register(key, container)
    }

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
    ContainerIndex.clear()
    FactBase.clear()
  }

  /**
   * 蓝图与实例分离：基于已编译脚本（蓝图）生成新的运行时实例。
   *
   * 设计目标（见 Klaymore 方案 §3.3 / §5.3）：
   * - 同一份脚本源码编译后只保留一份 CompiledScript（蓝图）。
   * - 运行时根据需要 spawn 多个实例，每个实例可绑定不同 target / path。
   * - 例如 100 个士兵共用 soldier.kts 一份字节码，每个实例有自己的 path 与 target。
   *
   * @param parentContainer 父容器（用于建立父子关系、推导路径前缀）
   * @param scriptName 脚本名称（用于在 ScriptLoader 缓存中查找已编译蓝图）
   * @param target 新实例的绑定目标
   * @param path 新实例的逻辑路径（如 "/game/red/soldier_3"）
   * @param extraPersistentData 额外的持久化数据（会与 klaymore.path 合并）
   * @param side 端侧
   * @return 新容器；若蓝图未编译或实例化失败则返回 null
   */
  @JvmStatic
  fun spawnChild(
      parentContainer: ScriptContainer,
      scriptName: String,
      target: Any,
      path: String,
      extraPersistentData: Map<String, *>? = null,
      side: ScriptSide = ScriptSide.SERVER
  ): ScriptContainer? {
    // 1. 从蓝图缓存中获取已编译脚本（不重新编译）
    val compiled = ScriptLoader.getCachedCompiledScript(scriptName)
    if (compiled == null) {
      ScriptErrorReporter.report("spawnChild 失败：脚本 '$scriptName' 未预编译（蓝图不存在）")
      return null
    }

    // 2. 组装 initialPersistentData，包含路径
    val persistentData = mutableMapOf<String, Any?>()
    if (extraPersistentData != null) {
      persistentData.putAll(extraPersistentData)
    }
    persistentData[PATH_KEY] = path

    // 3. 复用 finishMount 完成实例化、注入、注册
    return finishMount(compiled, scriptName, target, parentContainer, persistentData, side)
  }

  private fun resolveEffectiveTarget(
      scriptInstance: Any,
      providedTarget: Any,
      scriptName: String
  ): Any {
    if (providedTarget is Dummy) return providedTarget

    // 约定方法方式：bindTarget(Dummy)
    val wantsDummyByMethod =
        scriptInstance::class.java.declaredMethods.any { method ->
          method.name == "bindTarget" &&
              method.parameterCount == 1 &&
              method.parameterTypes[0] == Dummy::class.java
        }

    // 字段注入方式：lateinit var target: Dummy
    val wantsDummyByField =
        scriptInstance::class.java.declaredFields.any { field ->
          field.name == "target" && field.type == Dummy::class.java
        }

    if ((wantsDummyByMethod || wantsDummyByField) && providedTarget !is Dummy) {
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
  private val CONVENTION_FIELD_NAMES =
      setOf("target", "container", "selfContainer", "net", "parent")

  private fun looksLikeScriptImpl(obj: Any): Boolean {
    val methods = obj.javaClass.declaredMethods
    // 有任意约定方法 → 是脚本
    if (methods.any { it.name in CONVENTION_METHOD_NAMES }) return true
    // 有约定字段（lateinit var target / container / net 等）→ 是脚本
    val fields = obj.javaClass.declaredFields
    if (fields.any {
      it.name in CONVENTION_FIELD_NAMES && !java.lang.reflect.Modifier.isStatic(it.modifiers)
    })
        return true
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
