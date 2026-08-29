// SubscriberRegistry.kt
package com.earthforge.klaymore.script

import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.reflect.KClass

object SubscriberRegistry {
  data class Handler(val instance: Any, val method: Method)

  private val registry = mutableMapOf<KClass<out Any>, WeakHashMap<Any, MutableList<Handler>>>()

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

  @JvmStatic
  fun dispatch(event: Any, target: Any): Boolean {
    val handlers = registry[event::class]?.get(target) ?: return false
    handlers.forEach { handler ->
      try {
        handler.method.invoke(handler.instance, event)
      } catch (e: Exception) {
        ScriptErrorReporter.report("执行事件处理器失败: ${handler.method.name}, 错误: ${e.message}")
      }
    }
    return handlers.isNotEmpty()
  }
}
