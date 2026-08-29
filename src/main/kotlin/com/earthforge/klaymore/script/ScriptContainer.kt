// ScriptContainer.kt
package com.earthforge.klaymore.script

import java.lang.ref.WeakReference
import kotlin.script.experimental.api.CompiledScript

class ScriptContainer(
    val scriptName: String,
    compiledScript: CompiledScript,
    target: Any? = null,
    parent: ScriptContainer? = null,
    scriptInstance: Any? = null
) {
  private var _compiledScript: CompiledScript = compiledScript
  private var _scriptInstance: Any? = scriptInstance

  private val _targetRef = WeakReference(target)
  var parent: ScriptContainer? = parent
    private set

  private val _children = mutableListOf<ScriptContainer>()
  val children: List<ScriptContainer>
    get() = _children

  private val tempDataMap = mutableMapOf<String, Any?>()
  private val persistentDataMap = mutableMapOf<String, Any?>()

  fun getTemp(key: String): Any? = tempDataMap[key]

  fun setTemp(key: String, value: Any?) {
    tempDataMap[key] = value
  }

  @Deprecated(
      "Use getTemp() or getPersistent() for explicit semantics", ReplaceWith("getTemp(key)"))
  fun getData(key: String): Any? = getTemp(key)

  @Deprecated("Use setTemp() or setPersistent() for explicit semantics",
      ReplaceWith("setTemp(key, value)"))
  fun setData(key: String, value: Any?) = setTemp(key, value)

  fun getPersistent(key: String): Any? = persistentDataMap[key]

  fun setPersistent(key: String, value: Any?) {
    if (value != null && !isSafePersistentType(value)) {
      ScriptErrorReporter.report(
          "Warning: Attempted to store non-serializable type '${value.javaClass.simpleName}' " +
              "in persistentDataMap for key '$key'. It may not serialize correctly.")
    }
    persistentDataMap[key] = value
  }

  fun exportPersistentData(): Map<String, *> = persistentDataMap.toMap()

  fun importPersistentData(data: Map<String, *>) {
    persistentDataMap.clear()
    persistentDataMap.putAll(data)
  }

  private fun isSafePersistentType(value: Any): Boolean {
    return when (value) {
      is String, is Boolean, is Number, is Char -> true
      is List<*> -> value.all { it == null || isSafePersistentType(it) }
      is Map<*, *> ->
          value.all { (k, v) ->
            (k == null || k is String || k is Number || k is Boolean || k is Char) &&
                (v == null || isSafePersistentType(v))
          }
      is Array<*> -> value.all { it == null || isSafePersistentType(it) }
      else -> false
    }
  }

  fun getTarget(): Any? = _targetRef.get()

  fun getScriptInstance(): Any? = _scriptInstance

  fun getCompiledScript(): CompiledScript = _compiledScript

  fun setParent(parent: ScriptContainer?) {
    this.parent?.removeChild(this)
    this.parent = parent
    parent?.addChild(this)
  }

  fun addChild(child: ScriptContainer) {
    if (child !in _children) {
      _children.add(child)
      child.parent = this
    }
  }

  fun removeChild(child: ScriptContainer) {
    if (_children.remove(child)) {
      child.parent = null
    }
  }

  internal fun onUnmount() {
    val target = _targetRef.get()
    if (target != null) {
      SubscriberRegistry.unregisterAll(target)
    }
    _targetRef.clear()
  }

  fun replaceScriptInstance(newInstance: Any, newCompiled: CompiledScript) {
    val oldInstance = _scriptInstance
    if (oldInstance != null) {
      SubscriberRegistry.unregisterInstance(oldInstance)
    }

    _compiledScript = newCompiled
    _scriptInstance = newInstance

    val target = _targetRef.get()
    val parentTarget = parent?.getTarget()

    ScriptInjectionUtils.injectConventions(newInstance, target, parentTarget, this)
    if (target != null) {
      ScriptInjectionUtils.registerSubscribers(newInstance, target)
    }
  }

  fun run(methodName: String, vararg args: Any?): Any? {
    val instance =
        _scriptInstance
            ?: throw IllegalStateException("Script instance for '$scriptName' is not available")
    val methods =
        instance::class.java.declaredMethods.filter {
          it.name == methodName && it.parameterCount == args.size
        }
    if (methods.isEmpty()) {
      throw NoSuchMethodException(
          "Method '$methodName' with ${args.size} parameter(s) not found in script '$scriptName'")
    }
    val method = methods.first()
    method.isAccessible = true
    return method.invoke(instance, *args)
  }
}

object ScriptInjectionUtils {
  @JvmStatic
  fun invokeConventionMethod(instance: Any, methodName: String, arg: Any?) {
    if (arg == null) return
    val argClass = arg.javaClass
    val methods =
        instance::class.java.declaredMethods.filter {
          it.name == methodName && it.parameterCount == 1
        }

    val sortedMethods =
        methods.sortedWith(
            compareBy { method ->
              val paramType = method.parameterTypes[0]
              when {
                paramType == argClass -> 0
                paramType.isAssignableFrom(argClass) -> 1
                paramType == Any::class.java -> 2
                else -> 3
              }
            })

    val targetMethod = sortedMethods.firstOrNull() ?: return

    try {
      targetMethod.isAccessible = true
      targetMethod.invoke(instance, arg)
    } catch (e: Exception) {
      ScriptErrorReporter.report("调用约定方法 $methodName 失败: ${e.message}")
    }
  }

  @JvmStatic
  fun injectConventions(
      instance: Any,
      target: Any?,
      parentTarget: Any?,
      container: ScriptContainer
  ) {
    invokeConventionMethod(instance, "bindTarget", target)
    invokeConventionMethod(instance, "bindParent", parentTarget)
    invokeConventionMethod(instance, "bindContainer", container)
  }

  @JvmStatic
  fun registerSubscribers(instance: Any, target: Any) {
    val methods = instance::class.java.declaredMethods
    for (method in methods) {
      val annotation = method.getAnnotation(Subscribe::class.java) ?: continue

      val modifiers = method.modifiers
      if (java.lang.reflect.Modifier.isStatic(modifiers)) {
        ScriptErrorReporter.report(
            "${method.name} 被 @Subscribe 标记但为静态方法，已忽略（仅支持实例方法）")
        continue
      }
      if (method.parameterCount != 1) {
        ScriptErrorReporter.report(
            "${method.name} 被 @Subscribe 标记但参数数量不为 1，已忽略（需要恰好一个事件参数）")
        continue
      }

      val expectedEventType = annotation.event.java
      val actualParamType = method.parameterTypes[0]
      if (!expectedEventType.isAssignableFrom(actualParamType)) {
        ScriptErrorReporter.report(
            "${method.name} 注解事件类型 ${expectedEventType.simpleName} 与方法参数类型 ${actualParamType.simpleName} 不兼容，已忽略")
        continue
      }

      SubscriberRegistry.register(
          eventType = annotation.event,
          target = target,
          handler = SubscriberRegistry.Handler(instance, method))
    }
  }
}
