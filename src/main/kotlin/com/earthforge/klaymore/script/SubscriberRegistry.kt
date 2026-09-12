package com.earthforge.klaymore.script

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.reflect.KClass

object SubscriberRegistry {
  /** 事件处理器，使用 [MethodHandle] 而非 [Method] 提升高频事件性能 */
  data class Handler(val instance: Any, val methodHandle: MethodHandle, val side: ScriptSide) {
    companion object {
      /** 从 [Method] 创建 [MethodHandle]，自动处理可访问性。 */
      fun fromMethod(instance: Any, method: Method, side: ScriptSide): Handler {
        method.setAccessible(true)
        val handle =
            try {
              MethodHandles.lookup().unreflect(method)
            } catch (_: IllegalAccessException) {
              MethodHandles.publicLookup().unreflect(method)
            }
        return Handler(instance, handle, side)
      }
    }
  }

  private val registry = mutableMapOf<KClass<out Any>, WeakHashMap<Any, MutableList<Handler>>>()
  private val subscribedClasses = java.util.concurrent.ConcurrentHashMap.newKeySet<Class<*>>()
  private val hasSubscriberCache = java.util.concurrent.ConcurrentHashMap<Class<*>, Boolean>()

  fun register(eventType: KClass<out Any>, target: Any, handler: Handler) {
    val objectMap = registry.getOrPut(eventType) { WeakHashMap() }
    objectMap.getOrPut(target) { mutableListOf() }.add(handler)
    subscribedClasses.add(eventType.java)
    hasSubscriberCache.clear()
  }

  fun unregisterAll(target: Any) {
    registry.values.forEach { it.remove(target) }
    cleanupEmptyEventTypes()
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
    cleanupEmptyEventTypes()
  }

  /** 移除已无 target 的事件类并重算缓存 */
  private fun cleanupEmptyEventTypes() {
    val stillActive = registry.keys.map { it.java }.toSet()
    subscribedClasses.retainAll(stillActive)
    hasSubscriberCache.clear()
  }

  /** 沿继承链判断该事件类是否有任何订阅者 */
  private fun hasAnySubscriber(eventClass: Class<*>): Boolean {
    hasSubscriberCache[eventClass]?.let {
      return it
    }
    var current: Class<*>? = eventClass
    while (current != null) {
      if (subscribedClasses.contains(current)) {
        hasSubscriberCache[eventClass] = true
        return true
      }
      current = current.superclass
    }
    hasSubscriberCache[eventClass] = false
    return false
  }

  /** Kotlin 提取器注册，实际写入到 Java 的 EventTargetRegistrar */
  @Suppress("UNCHECKED_CAST", "RemoveExplicitTypeArguments", "UNUSED_CHANGED_VALUE")
  @JvmStatic
  fun <T : Any> registerExtractor(eventClass: Class<T>, extractor: (T) -> Any?) {
    val asJavaFunction: java.util.function.Function<T, *> =
        java.util.function.Function { event ->
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

  /** Java Function 提取器注册。preInit 早期请使用纯 Java 的 EventTargetExtractorRegistry.registerExtractor */
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
  fun getManagedEventClasses(): Set<Class<*>> = EventTargetRegistrar.getManagedEventClasses()

  /** 预热 Kotlin 运行时，需在 KotlinPreloader.preload() 之后调用 */
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

  private fun isClientSide(event: Any): Boolean {
    // 优先通过事件里的 World/Entity 判断 isRemote —— 集成服务器中最可靠
    try {
      val ev = event as Any
      val worldCandidates = mutableListOf<Any?>()
      // 常见字段: player / entity / world / worldObj
      val tryFields = arrayOf("player", "entity", "living", "p", "e")
      for (name in tryFields) {
        try {
          val f = ev.javaClass.getField(name) ?: continue
          f.isAccessible = true
          worldCandidates.add(f.get(ev))
        } catch (_: Throwable) {}
        try {
          val m =
              ev.javaClass.getMethod(
                  "get" +
                      name.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase() else it.toString()
                      }) ?: continue
          worldCandidates.add(m.invoke(ev))
        } catch (_: Throwable) {}
      }
      // 直接带 world 的字段: world / worldObj
      val tryWorldFields = arrayOf("world", "worldObj")
      for (name in tryWorldFields) {
        try {
          val f = ev.javaClass.getField(name) ?: continue
          f.isAccessible = true
          worldCandidates.add(f.get(ev))
        } catch (_: Throwable) {}
        try {
          val m =
              ev.javaClass.getMethod(
                  "get" +
                      name.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase() else it.toString()
                      }) ?: continue
          worldCandidates.add(m.invoke(ev))
        } catch (_: Throwable) {}
      }
      for (obj in worldCandidates) {
        if (obj == null) continue
        var w: Any? = obj
        // 如果拿到的是 Entity（含 Player），再往里取 .worldObj / .world
        try {
          val wf = obj.javaClass.getField("worldObj")
          wf.isAccessible = true
          val v = wf.get(obj)
          if (v != null) w = v
        } catch (_: Throwable) {
          try {
            val wm = obj.javaClass.getMethod("getWorldObj")
            val v = wm.invoke(obj)
            if (v != null) w = v
          } catch (_: Throwable) {}
        }
        try {
          val wf = obj.javaClass.getField("world")
          wf.isAccessible = true
          val v = wf.get(obj)
          if (v != null) w = v
        } catch (_: Throwable) {
          try {
            val wm = obj.javaClass.getMethod("getWorld")
            val v = wm.invoke(obj)
            if (v != null) w = v
          } catch (_: Throwable) {}
        }
        // 检查 World.isRemote
        if (w != null) {
          try {
            val rf = w.javaClass.getField("isRemote")
            rf.isAccessible = true
            if (rf.getBoolean(w)) return true
            // 找到且为 false（服务端），就不用继续查了
            return false
          } catch (_: Throwable) {
            try {
              val rm = w.javaClass.getMethod("isRemote")
              val r = rm.invoke(w) as? Boolean
              if (r != null) return r
            } catch (_: Throwable) {}
          }
        }
      }
    } catch (_: Throwable) {}
    // Fallback: 用 FML CommonHandler 判断线程所属 side
    return try {
      val fmlCls = Class.forName("cpw.mods.fml.common.FMLCommonHandler")
      val inst = fmlCls.getMethod("instance").invoke(null)
      val side = fmlCls.getMethod("getEffectiveSide").invoke(inst)
      val sideCls = Class.forName("cpw.mods.fml.relauncher.Side")
      val clientConst = sideCls.getField("CLIENT").get(null)
      side == clientConst
    } catch (_: Throwable) {
      false
    }
  }

  @JvmStatic
  fun dispatch(event: Any): Boolean {
    val eventClass = event.javaClass
    if (EventTargetRegistrar.isEventSkipped(eventClass)) {
      return false
    }
    if (!hasAnySubscriber(eventClass)) {
      return false
    }
    val eventSide = if (isClientSide(event)) ScriptSide.CLIENT else ScriptSide.SERVER
    val javaExtractor = EventTargetRegistrar.findExtractor(eventClass)
    val called = HashSet<Handler>()
    var dispatched = false
    if (javaExtractor != null) {
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
        for (t in candidates) {
          if (dispatchToTarget(event, t, called, eventSide)) {
            dispatched = true
          }
        }
      } else {
        com.earthforge.klaymore.Klaymore.LOG.debug(
            "[Klaymore] Extractor returned no valid target(s) for ${eventClass.simpleName}, skipping target dispatch (root still receives)")
      }
    } else {
      if (!EventTargetRegistrar.isKnownNoTargetEvent(eventClass)) {
        com.earthforge.klaymore.Klaymore.LOG.warn(
            "[Klaymore] No target extractor for event ${eventClass.simpleName}, dispatching to all subscribers")
      }
      if (dispatchToAllWithCalled(event, called, eventSide)) {
        dispatched = true
      }
    }
    // 根派发：服务端事件 → 服务端根；客户端事件 → 客户端根
    val rootTarget = if (eventSide.isServer()) GlobalRoot.target else GlobalRoot.clientTarget
    if (dispatchToTarget(event, rootTarget, called, eventSide)) {
      dispatched = true
    }
    return dispatched
  }

  private fun dispatchToAllWithCalled(
      event: Any,
      called: MutableSet<Handler>,
      eventSide: ScriptSide
  ): Boolean {
    var dispatched = false
    val exactMap = registry[event::class]
    if (exactMap != null) {
      for ((_, handlers) in exactMap) {
        for (handler in handlers) {
          if (handler.side != eventSide) continue
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
            if (handler.side != eventSide) continue
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

  @JvmStatic
  fun dispatch(event: Any, target: Any): Boolean {
    if (!hasAnySubscriber(event.javaClass)) {
      return false
    }
    val eventSide = if (isClientSide(event)) ScriptSide.CLIENT else ScriptSide.SERVER
    return dispatchToTarget(event, target, null, eventSide)
  }

  private fun dispatchToTarget(
      event: Any,
      target: Any,
      called: MutableSet<Handler>?,
      eventSide: ScriptSide
  ): Boolean {
    var dispatched = false
    val exactHandlers = registry[event::class]?.get(target)
    if (exactHandlers != null) {
      for (handler in exactHandlers) {
        if (handler.side != eventSide) continue
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
            if (handler.side != eventSide) continue
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
      handler.methodHandle.invoke(handler.instance, event)
    } catch (e: Throwable) {
      ScriptErrorReporter.report("执行事件处理器失败: ${handler.methodHandle}, 错误: ${e.message}")
    }
  }
}
