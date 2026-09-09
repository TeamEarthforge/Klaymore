// ScriptContainer.kt
package com.earthforge.klaymore.script

import java.lang.ref.WeakReference
import kotlin.script.experimental.api.CompiledScript

/** 持久化数据中存储路径的 key。由父脚本通过 initialPersistentData 传入。 */
const val PATH_KEY = "klaymore.path"

class ScriptContainer(
    val scriptName: String,
    compiledScript: CompiledScript,
    target: Any? = null,
    parent: ScriptContainer? = null,
    scriptInstance: Any? = null,
    val side: ScriptSide = ScriptSide.SERVER
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

  /**
   * 容器的逻辑路径（如 "/game/red/soldier_1"）。
   *
   * 设计目标（见 Klaymore 方案 §2.2 / §3.1）：
   * - 路径是值对象，天然可序列化，随 persistentData 落盘。
   * - 父子关系由路径前缀（startsWith）动态推导，而非存储在内存中。
   * - 不写在脚本源码里，由父脚本在运行时通过 initialPersistentData["klaymore.path"] 传入。
   */
  var path: String
    get() = persistentDataMap[PATH_KEY]?.toString() ?: ""
    private set(value) {
      persistentDataMap[PATH_KEY] = value
    }

  /** 从 initialPersistentData 提取路径并存储。由 finishMount 在导入数据后调用。 */
  internal fun syncPathFromPersistentData() {
    val p = persistentDataMap[PATH_KEY]?.toString() ?: ""
    // path setter 已写回 persistentDataMap，这里仅做归一化（确保非空字符串）
    if (p.isEmpty()) {
      persistentDataMap.remove(PATH_KEY)
    }
  }

  /** 基于路径判断是否为另一个容器的子级。 数学性质：路径集合构成树状偏序集，父子关系由前缀推导。 */
  fun isDescendantOf(ancestorPath: String): Boolean {
    val myPath = path
    if (myPath.isEmpty() || ancestorPath.isEmpty()) return false
    if (myPath == ancestorPath) return false
    return myPath.startsWith(ancestorPath) &&
        (myPath.length == ancestorPath.length || myPath[ancestorPath.length] == '/')
  }

  fun getTemp(key: String): Any? = tempDataMap[key]

  fun setTemp(key: String, value: Any?) {
    tempDataMap[key] = value
  }

  @Deprecated(
      "Use getTemp() or getPersistent() for explicit semantics", ReplaceWith("getTemp(key)"))
  fun getData(key: String): Any? = getTemp(key)

  @Deprecated(
      "Use setTemp() or setPersistent() for explicit semantics", ReplaceWith("setTemp(key, value)"))
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
    for ((key, value) in data) {
      persistentDataMap[key] = normalizePersistentValue(value)
    }
  }

  private fun normalizePersistentValue(value: Any?): Any? {
    return when (value) {
      null -> null
      is Double -> {
        if (value.isNaN() || value.isInfinite()) value
        else if (value == value.toLong().toDouble()) {
          val l = value.toLong()
          if (l >= Int.MIN_VALUE && l <= Int.MAX_VALUE) l.toInt() else l
        } else value
      }
      is Float -> {
        if (value.isNaN() || value.isInfinite()) value
        else if (value == value.toLong().toFloat()) {
          val l = value.toLong()
          if (l >= Int.MIN_VALUE && l <= Int.MAX_VALUE) l.toInt() else l
        } else value
      }
      is List<*> -> value.map { normalizePersistentValue(it) }
      is Map<*, *> -> {
        val result = mutableMapOf<String, Any?>()
        for ((k, v) in value) {
          result[k.toString()] = normalizePersistentValue(v)
        }
        result
      }
      is Array<*> -> value.map { normalizePersistentValue(it) }
      else -> value
    }
  }

  private fun isSafePersistentType(value: Any): Boolean {
    return when (value) {
      is String,
      is Boolean,
      is Number,
      is Char -> true
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

  fun getRoot(): ScriptContainer? = GlobalRoot.container

  fun getRootInstance(): Any? = GlobalRoot.getInstance()

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
    ScriptNetDispatcher.unregisterContainer(this)

    // 从 ContainerIndex 移除（Key -> Container 映射）
    val key = PersistenceManager.generateKey(getTarget() ?: Unit)
    if (!key.isNullOrEmpty()) {
      ContainerIndex.unregister(key)
    }

    val target = _targetRef.get()
    if (target != null) {
      SubscriberRegistry.unregisterAll(target)
    }
    _targetRef.clear()

    val oldInstance = _scriptInstance
    if (oldInstance != null) {
      SubscriberRegistry.unregisterInstance(oldInstance)
    }
    _scriptInstance = null
    _compiledScript = null as CompiledScript
    tempDataMap.clear()
    persistentDataMap.clear()
    _children.clear()
    parent = null
  }

  /** 当前 target 对应的 Key（由 PersistenceManager 生成）。 */
  fun getKey(): String? = PersistenceManager.generateKey(getTarget() ?: Unit)

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
      ScriptInjectionUtils.registerSubscribers(newInstance, target, side)
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
  /** 通过反射向脚本实例注入字段（target / container / net 等，定义在 KlaymoreScript 基类中）。 */
  @JvmStatic
  fun injectFields(instance: Any, target: Any?, parentTarget: Any?, container: ScriptContainer) {
    val net = ScriptNetImpl(container)
    setFieldIfAssignable(instance, "target", target, Any::class.java)
    setFieldIfAssignable(instance, "container", container, ScriptContainer::class.java)
    setFieldIfAssignable(instance, "selfContainer", container, ScriptContainer::class.java)
    setFieldIfAssignable(instance, "parent", parentTarget, Any::class.java)
    setFieldIfAssignable(instance, "net", net, ScriptNet::class.java)
  }

  /**
   * 沿继承链查找非静态同名字段并注入值。
   *
   * 字段定义在 [KlaymoreScript] 基类中，子类的 declaredFields 不包含父类字段，
   * 因此需要向上遍历继承链直到找到目标字段。
   */
  private fun setFieldIfAssignable(
      instance: Any,
      fieldName: String,
      value: Any?,
      expectedType: Class<*>
  ) {
    try {
      val field = findFieldInHierarchy(instance.javaClass, fieldName) ?: return
      if (!field.type.isAssignableFrom(expectedType) && expectedType != Any::class.java) return
      field.isAccessible = true
      field.set(instance, value)
    } catch (e: Throwable) {
      // 注入失败时静默忽略
    }
  }

  private fun findFieldInHierarchy(
      clazz: Class<*>,
      fieldName: String
  ): java.lang.reflect.Field? {
    var current: Class<*>? = clazz
    while (current != null && current != Any::class.java) {
      try {
        val field = current.getDeclaredField(fieldName)
        if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) return field
      } catch (_: NoSuchFieldException) {
        // 继续向上找父类
      }
      current = current.superclass
    }
    return null
  }

  @JvmStatic
  fun injectConventions(
      instance: Any,
      target: Any?,
      parentTarget: Any?,
      container: ScriptContainer
  ) {
    injectFields(instance, target, parentTarget, container)
  }

  @JvmStatic
  fun registerSubscribers(instance: Any, target: Any, side: ScriptSide = ScriptSide.SERVER) {
    val methods = instance::class.java.declaredMethods
    for (method in methods) {
      val annotation = method.getAnnotation(Subscribe::class.java) ?: continue

      val modifiers = method.modifiers
      if (java.lang.reflect.Modifier.isStatic(modifiers)) {
        ScriptErrorReporter.report("${method.name} 被 @Subscribe 标记但为静态方法，已忽略（仅支持实例方法）")
        continue
      }
      if (method.parameterCount != 1) {
        ScriptErrorReporter.report("${method.name} 被 @Subscribe 标记但参数数量不为 1，已忽略（需要恰好一个事件参数）")
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
          handler = SubscriberRegistry.Handler.fromMethod(instance, method, side))
    }
  }
}
