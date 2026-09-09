package com.earthforge.klaymore.util

import com.earthforge.klaymore.Klaymore
import java.lang.invoke.MethodHandles
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function

/**
 * 全局脚本 API 注册表。
 *
 * 脚本可以把自身的方法以名称注入到这里，其他类（Java 或脚本）通过名称 + 参数调用。
 *
 * 设计要点：
 * - 内部存储 `(Array<Any?>) -> Any?`，统一动态调用。
 * - 通过反射 `Method` 注册时，内部转换为 `MethodHandle` 缓存调用点，性能接近直接调用。
 * - 线程安全，使用 `ConcurrentHashMap`。
 * - 所有方法标注 `@JvmStatic`，Java 可直接 `ScriptAPIs.invoke(...)` 调用。
 *
 * Kotlin 脚本注册示例：
 * ```kotlin
 * ScriptAPIs.register("myMod.say") { args ->
 *     val msg = args[0] as String
 *     println("got: $msg")
 *     "ok"
 * }
 * ```
 *
 * Java 调用示例：
 * ```java
 * String result = (String) ScriptAPIs.invoke("myMod.say", "hello");
 * ```
 */
object ScriptAPIs {

  private val apis = ConcurrentHashMap<String, (Array<Any?>) -> Any?>()

  /**
   * 注册一个 API。
   *
   * @param name API 名称，建议用 `"namespace.method"` 形式避免冲突。重复注册会覆盖并打 warn。
   * @param func 接收参数数组，返回结果（可为 null / Unit）。
   */
  @JvmStatic
  fun register(name: String, func: (Array<Any?>) -> Any?) {
    val prev = apis.put(name, func)
    if (prev != null) {
      Klaymore.LOG.warn("[Klaymore ScriptAPIs] API '$name' overwritten")
    }
  }
  /**
   * 通过反射 `Method` 注册。
   *
   * 内部会创建 `MethodHandle` 并 `bindTo(instance)` 缓存调用点，后续调用性能接近直接调用。
   *
   * @param name API 名称
   * @param instance 方法所属实例
   * @param method 要注册的方法
   */
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

  /**
   * 按名称调用 API。
   *
   * @param name API 名称
   * @param args 传给 API 的参数
   * @return API 的返回值；若 API 不存在或执行出错则返回 null（并记录日志）。
   */
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

  /**
   * 按名称调用，不存在时静默返回 null（不打 warn），执行出错仍记录 error。
   *
   * 用于调用方不关心 API 是否存在的场景。
   */
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
  @JvmStatic
  fun has(name: String): Boolean = apis.containsKey(name)

  /** 注销某个 API，返回是否存在并成功移除。 */
  @JvmStatic
  fun unregister(name: String): Boolean = apis.remove(name) != null

  /** 获取所有已注册的 API 名称（快照）。 */
  @JvmStatic
  fun names(): Set<String> = apis.keys.toSet()

  /** 清空所有 API（慎用，通常仅在脚本重载时调用）。 */
  @JvmStatic
  fun clear() {
    apis.clear()
  }
}
