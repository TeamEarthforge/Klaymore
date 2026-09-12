package com.earthforge.klaymore.util

import com.earthforge.klaymore.Klaymore
import java.lang.invoke.MethodHandles
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** 全局脚本 API 注册表，通过名称 + 参数动态调用 */
object ScriptAPIs {

  private val apis = ConcurrentHashMap<String, (Array<Any?>) -> Any?>()

  /** 注册一个 API */
  @JvmStatic
  fun register(name: String, func: (Array<Any?>) -> Any?) {
    val prev = apis.put(name, func)
    if (prev != null) {
      Klaymore.LOG.warn("[Klaymore ScriptAPIs] API '$name' overwritten")
    }
  }

  /** 通过反射 Method 注册 */
  @JvmStatic
  fun register(name: String, instance: Any, method: Method) {
    method.setAccessible(true)
    val handle =
        try {
          MethodHandles.lookup().unreflect(method).bindTo(instance)
        } catch (_: IllegalAccessException) {
          MethodHandles.publicLookup().unreflect(method).bindTo(instance)
        }
    register(name) { args -> handle.invokeWithArguments(*args) }
  }

  /** 按名称调用 API */
  @JvmStatic
  fun invoke(name: String, vararg args: Any?): Any? {
    val func = apis[name]
    if (func == null) {
      Klaymore.LOG.warn("[Klaymore ScriptAPIs] API '$name' not found")
      return null
    }
    return try {
      func(arrayOf(*args))
    } catch (t: Throwable) {
      Klaymore.LOG.error("[Klaymore ScriptAPIs] Error invoking '$name': ${t.message}", t)
      null
    }
  }

  /** 按名称调用，不存在时静默返回 null */
  @JvmStatic
  fun invokeOrNull(name: String, vararg args: Any?): Any? {
    val func = apis[name] ?: return null
    return try {
      func(arrayOf(*args))
    } catch (t: Throwable) {
      Klaymore.LOG.error("[Klaymore ScriptAPIs] Error invoking '$name': ${t.message}", t)
      null
    }
  }

  /** 判断某名称的 API 是否已注册。 */
  @JvmStatic fun has(name: String): Boolean = apis.containsKey(name)

  /** 注销某个 API，返回是否存在并成功移除。 */
  @JvmStatic fun unregister(name: String): Boolean = apis.remove(name) != null

  /** 获取所有已注册的 API 名称（快照）。 */
  @JvmStatic fun names(): Set<String> = apis.keys.toSet()

  /** 清空所有 API */
  @JvmStatic
  fun clear() {
    apis.clear()
  }
}
