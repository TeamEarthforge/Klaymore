package com.earthforge.klaymore.script

import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.reflect.KClass

object SubscriberRegistry {
  data class Handler(val instance: Any, val method: Method)

  private val registry =
      mutableMapOf<KClass<out Any>, WeakHashMap<Any, MutableList<Handler>>>()

  fun register(eventType: KClass<out Any>, target: Any, handler: Handler) {
    val objectMap = registry.getOrPut(eventType) { WeakHashMap() }
    objectMap.getOrPut(target) { mutableListOf() }.add(handler)
  }

  fun unregisterAll(target: Any) {
    registry.values.forEach { it.remove(target) }
  }

  fun unregisterInstance(instance: Any) {
    val eventTypesToClean = mutableListOf<KClass<out Any>>()

    for ((eventType, objectMap) in registry) {
      val targetsToClean = mutableListOf<Any>()

      for ((target, handlers) in objectMap) {
        handlers.removeAll { it.instance === instance }
        if (handlers.isEmpty()) {
          targetsToClean.add(target)
        }
      }

      targetsToClean.forEach { objectMap.remove(it) }
      if (objectMap.isEmpty()) {
        eventTypesToClean.add(eventType)
      }
    }

    eventTypesToClean.forEach { registry.remove(it) }
  }

  /**
   * Kotlin 原生提取器注册（给 Kotlin 代码调用）。
   * 注意：提取器实际写入到纯 Java 的 EventTargetRegistrar，避免 preInit 早期类加载问题。
   */
  @Suppress(
      "UNCHECKED_CAST",
      "RemoveExplicitTypeArguments",
      "UNUSED_CHANGED_VALUE"
  )
  @JvmStatic
  fun <T : Any> registerExtractor(
      eventClass: Class<T>,
      extractor: (T) -> Any?
  ) {
    val asJavaFunction: java.util.function.Function<T, *> = java.util.function.Function { event ->
      try {
        extractor(event)
      } catch (e: Exception) {
        com.earthforge.klaymore.Klaymore.LOG.warn(
            "[Klaymore] Kotlin extractor failed for ${eventClass.simpleName}: ${e.message}")
        null
      }
    }
    // Kotlin 对 Java 通配符泛型要求过于严格。Java 侧有自己的 unchecked 转换，
    // 我们这里直接用 raw 参数调用，让 Java 层再做类型处理。
    val rawClass = eventClass as Class<Nothing>
    val rawFn = asJavaFunction as java.util.function.Function<Nothing, *>
    EventTargetRegistrar.registerExtractor(rawClass, rawFn)
  }

  /**
   * Java Function 友好的提取器注册入口。
   *
   * ⚠️ 警告：preInit 早期不要通过 SubscriberRegistry 调用此方法
   * （因为进入 Kotlin 方法体之前 JVM 需要先解析 Intrinsics，可能触发类加载 NPE）。
   * 请直接通过 `EventTargetExtractorRegistry.registerExtractor` 注册 —— 它是纯 Java 实现，
   * 会写入到同一个 EventTargetRegistrar 中。
   */
  @Suppress("UNCHECKED_CAST")
  @JvmStatic
  fun <T : Any> registerExtractorJava(
      eventClass: Class<T>,
      extractor: java.util.function.Function<T, *>
  ) {
    val rawClass = eventClass as Class<Nothing>
    val rawFn = extractor as java.util.function.Function<Nothing, *>
    EventTargetRegistrar.registerExtractor(rawClass, rawFn)
  }

  @JvmStatic
  fun markAsNoTargetEvent(eventClass: Class<*>) {
    if (cpw.mods.fml.common.eventhandler.Event::class.java.isAssignableFrom(eventClass)) {
      @Suppress("UNCHECKED_CAST")
      EventTargetRegistrar.markAsNoTargetEvent(
          eventClass as Class<out cpw.mods.fml.common.eventhandler.Event>)
    }
  }

  @JvmStatic
  fun markAsSkippedEvent(eventClass: Class<*>) {
    if (cpw.mods.fml.common.eventhandler.Event::class.java.isAssignableFrom(eventClass)) {
      @Suppress("UNCHECKED_CAST")
      EventTargetRegistrar.markAsSkippedEvent(
          eventClass as Class<out cpw.mods.fml.common.eventhandler.Event>)
    }
  }

  @JvmStatic
  fun isEventSkipped(eventClass: Class<*>): Boolean =
      EventTargetRegistrar.isEventSkipped(eventClass)

  @JvmStatic
  fun getManagedEventClasses(): Set<Class<*>> =
      EventTargetRegistrar.getManagedEventClasses()

  /**
   * Kotlin 侧预热运行时。
   * 注意：调用此方法前请确保 KotlinPreloader.preload() 已在纯 Java 层执行过，
   * 否则第一次进入 Kotlin 方法体就可能触发 NoClassDefFoundError: Intrinsics。
   */
  @JvmStatic
  fun preloadKotlinStdlib() {
    try {
      val probe: (Any) -> Any? = { null }
      probe.javaClass
      kotlin.jvm.internal.Intrinsics::class.java
      Unit::class.java
      listOf<Any>().javaClass
      com.earthforge.klaymore.Klaymore.LOG.info(
          "[Klaymore] Kotlin stdlib preloaded OK (Kotlin-runtime class-loader path warmed up)")
    } catch (t: Throwable) {
      com.earthforge.klaymore.Klaymore.LOG.warn(
          "[Klaymore] Kotlin stdlib preload issued warning: ${t.message} (continuing anyway)")
    }
  }

  @JvmStatic
  fun dispatch(event: Any): Boolean {
    val eventClass = event.javaClass
    if (EventTargetRegistrar.isEventSkipped(eventClass)) {
      return false
    }
    val javaExtractor = EventTargetRegistrar.findExtractor(eventClass)
    return if (javaExtractor != null) {
      val rawTarget =
          try {
            javaExtractor.apply(event)
          } catch (e: Exception) {
            com.earthforge.klaymore.Klaymore.LOG.warn(
                "[Klaymore] Target extractor threw exception for event ${eventClass.simpleName}: ${e.message}")
            null
          }
      val candidates = EventTargetRegistrar.flattenTargets(rawTarget)
      if (candidates.isNotEmpty()) {
        com.earthforge.klaymore.Klaymore.LOG.debug(
            "[Klaymore] Dispatching ${eventClass.simpleName} to ${candidates.size} target(s): $candidates")
        val called = HashSet<Handler>()
        var dispatched = false
        for (t in candidates) {
          if (dispatchToTarget(event, t, called)) {
            dispatched = true
          }
        }
        dispatched
      } else {
        com.earthforge.klaymore.Klaymore.LOG.debug(
            "[Klaymore] Extractor returned no valid target(s) for ${eventClass.simpleName}, skipping dispatch (no broadcast for extractor-registered events)")
        false
      }
    } else {
      if (!EventTargetRegistrar.isKnownNoTargetEvent(eventClass)) {
        com.earthforge.klaymore.Klaymore.LOG.warn(
            "[Klaymore] No target extractor for event ${eventClass.simpleName}, dispatching to all subscribers")
      }
      dispatchToAll(event)
    }
  }

  @JvmStatic
  fun dispatch(event: Any, target: Any): Boolean {
    return dispatchToTarget(event, target, null)
  }

  private fun dispatchToTarget(event: Any, target: Any, called: MutableSet<Handler>?): Boolean {
    var dispatched = false
    val exactHandlers = registry[event::class]?.get(target)
    if (exactHandlers != null) {
      for (handler in exactHandlers) {
        if (called == null || called.add(handler)) {
          safeInvoke(handler, event)
          dispatched = true
        }
      }
    }
    for ((eventType, objectMap) in registry) {
      if (eventType.java.isAssignableFrom(event.javaClass)) {
        val handlers = objectMap[target]
        if (handlers != null && eventType != event::class) {
          for (handler in handlers) {
            if (called == null || called.add(handler)) {
              safeInvoke(handler, event)
              dispatched = true
            }
          }
        }
      }
    }
    return dispatched
  }

  private fun dispatchToAll(event: Any): Boolean {
    var dispatched = false
    val called = HashSet<Handler>()
    val exactMap = registry[event::class]
    if (exactMap != null) {
      for ((_, handlers) in exactMap) {
        for (handler in handlers) {
          if (called.add(handler)) {
            safeInvoke(handler, event)
            dispatched = true
          }
        }
      }
    }
    for ((eventType, objectMap) in registry) {
      if (eventType != event::class && eventType.java.isAssignableFrom(event.javaClass)) {
        for ((_, handlers) in objectMap) {
          for (handler in handlers) {
            if (called.add(handler)) {
              safeInvoke(handler, event)
              dispatched = true
            }
          }
        }
      }
    }
    return dispatched
  }

  private fun safeInvoke(handler: Handler, event: Any) {
    try {
      handler.method.invoke(handler.instance, event)
    } catch (e: Exception) {
      ScriptErrorReporter.report(
          "执行事件处理器失败: ${handler.method.name}, 错误: ${e.message}")
    }
  }
}
