// SubscriberRegistry.kt
package com.earthforge.klaymore.script

import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.reflect.KClass

object SubscriberRegistry {
  data class Handler(val instance: Any, val method: Method)

  private val registry = mutableMapOf<KClass<out Any>, WeakHashMap<Any, MutableList<Handler>>>()

  /**
   * 注册一个处理器
   *
   * @param eventType 事件类型（KClass）
   * @param target 绑定的目标对象（如 EntityPlayer），作为查找键
   * @param handler 包含脚本实例和方法的处理器
   */
  fun register(eventType: KClass<out Any>, target: Any, handler: Handler) {
    val objectMap = registry.getOrPut(eventType) { WeakHashMap() }
    objectMap.getOrPut(target) { mutableListOf() }.add(handler)
  }

  /** 注销某个目标对象的所有处理器（用于解绑/卸载时清理） */
  fun unregisterAll(target: Any) {
    registry.values.forEach { it.remove(target) }
  }

  /**
   * 派发事件：根据事件类型和触发目标，找到所有处理器并执行
   *
   * @return 是否成功找到并执行了处理器
   */
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
