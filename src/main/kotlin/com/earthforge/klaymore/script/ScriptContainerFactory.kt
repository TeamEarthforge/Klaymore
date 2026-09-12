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
      side: ScriptSide = ScriptSide.SERVER
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
      side: ScriptSide = ScriptSide.SERVER,
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
      // 客户端根跨世界常驻，不随世界退出卸载（否则客户端 @Subscribe 处理器丢失）
      if (container.getTarget() === GlobalRoot.clientTarget) continue
      try {
        container.parent?.removeChild(container)
        container.children.toList().forEach { unmount(it) }
        container.onUnmount()
        ScriptBindingManager.unregister(container)
      } catch (t: Throwable) {
        // 忽略单个容器的清理错误，继续清理其他
      }
    }
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

    // 全局脚本（覆写 isGlobal = true）不绑定具体 target，引擎分配 Dummy
    if (scriptInstance is KlaymoreScript && scriptInstance.isGlobal) {
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
          val rawInstance = instantiateKClass(kClass.java) ?: return null
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

  /**
   * 实例化脚本类。
   *
   * 支持两种脚本声明方式：
   * - `class Foo : KlaymoreScript()` → 调用无参构造创建新实例（每个 spawnChild 产生独立实例）
   * - `object Foo : KlaymoreScript()` → 返回单例 INSTANCE
   */
  private fun instantiateKClass(clazz: Class<*>): Any? {
    // 优先尝试 object 单例（INSTANCE 字段）
    try {
      val instanceField = clazz.getField("INSTANCE")
      return instanceField.get(null)
    } catch (_: Throwable) {
      // 非 object -> 尝试无参构造（class 声明）
    }
    try {
      val ctor = clazz.getDeclaredConstructor()
      ctor.isAccessible = true
      return ctor.newInstance()
    } catch (e: Throwable) {
      ScriptErrorReporter.report(
          "无法实例化脚本类 ${clazz.simpleName}：既非 object 单例也无无参构造。" +
              "请使用 class Xxx : KlaymoreScript() 或 object Xxx : KlaymoreScript()。")
      return null
    }
  }

  private fun looksLikeScriptImpl(obj: Any): Boolean =
      KlaymoreScript::class.java.isAssignableFrom(obj.javaClass)

  /**
   * 从脚本编译产物的外壳类中，找到继承 [KlaymoreScript] 的嵌套类并实例化。
   *
   * Kotlin 脚本编译后会生成一个外壳类，用户写的 `class Soldier : KlaymoreScript()` 会成为该外壳类的嵌套类。这里扫描所有嵌套类，找到第一个继承
   * KlaymoreScript 的， 通过无参构造 newInstance() 返回全新实例（每个 spawnChild 调用产生独立实例）。
   */
  private fun unwrapScriptObject(rawInstance: Any, scriptName: String): Any? {
    if (looksLikeScriptImpl(rawInstance)) return rawInstance

    val rawClass = rawInstance.javaClass
    try {
      for (declaredClass in rawClass.declaredClasses) {
        if (!KlaymoreScript::class.java.isAssignableFrom(declaredClass)) continue
        try {
          val instanceField = declaredClass.getField("INSTANCE")
          return instanceField.get(null)
        } catch (_: Throwable) {
          // 非 object -> 尝试无参构造
        }
        try {
          val ctor = declaredClass.getDeclaredConstructor()
          ctor.isAccessible = true
          return ctor.newInstance()
        } catch (e: Throwable) {
          ScriptErrorReporter.report("实例化脚本类 ${declaredClass.simpleName} 失败: ${e.message}")
        }
      }
    } catch (_: Throwable) {
      // ignore
    }

    ScriptErrorReporter.report(
        "脚本 $scriptName 中未找到继承 KlaymoreScript 的类。" + "脚本必须包含形如 'class Xxx : KlaymoreScript()' 的声明。")
    return null
  }

  @JvmStatic
  fun instantiateScriptForReload(compiledScript: CompiledScript, scriptName: String): Any? =
      instantiateScript(compiledScript, scriptName)
}
