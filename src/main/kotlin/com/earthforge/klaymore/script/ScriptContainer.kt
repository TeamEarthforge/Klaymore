// ScriptContainer.kt
package com.earthforge.klaymore.script

import java.lang.ref.WeakReference
import kotlin.script.experimental.api.CompiledScript


class ScriptContainer(
    val scriptName: String,
    private val compiledScript: CompiledScript,
    target: Any? = null,
    parent: ScriptContainer? = null,
    private val scriptInstance: Any? = null // 可选的实例，用于事件调用
) {
    // ---------- 运行时状态 ----------
    private val _targetRef = WeakReference(target)
    var parent: ScriptContainer? = parent
        private set
    private val _children = mutableListOf<ScriptContainer>()
    val children: List<ScriptContainer> get() = _children

    // ---------- 任意数据存储 ----------
    private val dataMap = mutableMapOf<String, Any?>()

    fun getData(key: String): Any? = dataMap[key]
    fun setData(key: String, value: Any?) { dataMap[key] = value }
    fun getDataMap(): Map<String, Any?> = dataMap.toMap()

    // ---------- 访问器 ----------
    fun getTarget(): Any? = _targetRef.get()
    fun getScriptInstance(): Any? = scriptInstance

    // ---------- 父子管理（与之前相同） ----------
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

    /**
     * 动态调用脚本实例上的指定方法。
     * @param methodName 方法名
     * @param args 可变参数，对应方法的参数列表（可为空）
     * @return 方法返回值（如果方法有返回值），若方法为 void 则返回 null
     * @throws IllegalStateException 如果脚本实例不存在
     * @throws NoSuchMethodException 如果找不到与参数数量匹配的方法
     * @throws Exception 调用过程中的其他异常（如类型不匹配、方法内部异常等）
     */
    fun run(methodName: String, vararg args: Any?): Any? {
        val instance = scriptInstance
            ?: throw IllegalStateException("Script instance for '$scriptName' is not available")
        val methods = instance::class.java.declaredMethods
            .filter { it.name == methodName && it.parameterCount == args.size }
        if (methods.isEmpty()) {
            throw NoSuchMethodException(
                "Method '$methodName' with ${args.size} parameter(s) not found in script '$scriptName'"
            )
        }
        val method = methods.first()
        method.isAccessible = true
        return method.invoke(instance, *args)
    }
}
