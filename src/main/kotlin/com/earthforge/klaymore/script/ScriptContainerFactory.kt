package com.earthforge.klaymore.script

import kotlinx.coroutines.runBlocking
import net.minecraft.launchwrapper.Launch
import java.io.File
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration

// ScriptContainerFactory.kt
object ScriptContainerFactory {
    /**
     * 构建一个已完全初始化的 ScriptContainer
     * @param scriptName 脚本名
     * @param scriptFile 脚本文件
     * @param target 绑定目标对象
     * @param parentContainer 父容器（可选）
     * @return 已挂载的 ScriptContainer，失败则返回 null
     */
    @JvmStatic
    fun createAndMount(
        scriptName: String,
        scriptFile: File,
        target: Any,
        parentContainer: ScriptContainer? = null
    ): ScriptContainer? {
        // 1. 编译脚本
        val compiled = ScriptLoader.loadScript(scriptFile) ?: return null

        // 2. 实例化脚本类
        val instance = instantiateScript(compiled, scriptName) ?: return null

        // 3. 创建容器（此时未挂载任何东西）
        val container = ScriptContainer(
            scriptName = scriptName,
            compiledScript = compiled,
            target = target,
            parent = parentContainer,
            scriptInstance = instance
        )

        // 4. 将父容器添加到子列表（双向绑定）
        parentContainer?.addChild(container)

        // 5. 调用脚本的约定方法（注入 target、parent、container）
        invokeConventionMethod(instance, "bindTarget", target)
        invokeConventionMethod(instance, "bindParent", parentContainer?.getTarget())
        invokeConventionMethod(instance, "bindContainer", container)

        // 6. 扫描 @Subscribe 注解并注册到全局注册表
        registerSubscribers(instance, target)

        // 7. 如果需要在容器中保存额外数据，可在此添加（略）

        return container
    }

    /**
     * 卸载容器并清理所有关联
     */
    @JvmStatic
    fun unmount(container: ScriptContainer) {
        // 1. 从父容器中移除自身
        container.parent?.removeChild(container)
        // 2. 清理所有子容器（递归）
        container.children.toList().forEach { unmount(it) }
        // 3. 调用内部清理（清除注册表）
        container.onUnmount()
        // 4. 可选：清除缓存引用等
    }

    // ---------- 私有辅助方法（与之前相同，但作为静态工具） ----------
    private fun instantiateScript(compiledScript: CompiledScript, scriptName: String): Any? {
        val originalLoader = Thread.currentThread().contextClassLoader
        return try {
            Thread.currentThread().contextClassLoader = Launch.classLoader
            val evalConfig = ScriptEvaluationConfiguration { } // 或直接传 null
            // 使用 runBlocking 将挂起调用转为阻塞调用
            val classResult = runBlocking {
                compiledScript.getClass(evalConfig)
            }
            when (classResult) {
                is ResultWithDiagnostics.Success -> {
                    val kClass = classResult.value
                    // 转换为 Java Class 并调用无参构造
                    kClass.java.getDeclaredConstructor().newInstance()
                }
                is ResultWithDiagnostics.Failure -> {
                    ScriptErrorReporter.report("获取脚本类失败: ${classResult.reports.joinToString { it.toString() }}")
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
    private fun invokeConventionMethod(instance: Any, methodName: String, arg: Any?) {
        if (arg == null) return  // 不处理 null 参数
        val argClass = arg.javaClass
        val methods = instance::class.java.declaredMethods
            .filter { it.name == methodName && it.parameterCount == 1 }

        val sortedMethods = methods.sortedWith(compareBy { method ->
            val paramType = method.parameterTypes[0]
            when {
                paramType == argClass -> 0
                paramType.isAssignableFrom(argClass) -> 1
                paramType == Any::class.java -> 2
                else -> 3
            }
        })

        val targetMethod = sortedMethods.firstOrNull()
        if (targetMethod == null) {
            // 没有找到任何方法，静默忽略（或可记录 debug）
            return
        }

        try {
            targetMethod.isAccessible = true
            targetMethod.invoke(instance, arg)
        } catch (e: Exception) {
            ScriptErrorReporter.report("调用约定方法 $methodName 失败: ${e.message}")
        }
    }

    private fun registerSubscribers(instance: Any, target: Any) {
        val methods = instance::class.java.declaredMethods
        for (method in methods) {
            val annotation = method.getAnnotation(Subscribe::class.java) ?: continue

            // 验证方法签名：非静态，一个参数
            val modifiers = method.modifiers
            if (java.lang.reflect.Modifier.isStatic(modifiers)) {
                ScriptErrorReporter.report("${method.name} 被 @Subscribe 标记但为静态方法，已忽略（仅支持实例方法）")
                continue
            }
            if (method.parameterCount != 1) {
                ScriptErrorReporter.report("${method.name} 被 @Subscribe 标记但参数数量不为 1，已忽略（需要恰好一个事件参数）")
                continue
            }

            // 验证注解上的事件类型是否与方法参数类型匹配（可选，但不强制）
            val expectedEventType = annotation.event.java
            val actualParamType = method.parameterTypes[0]
            if (!expectedEventType.isAssignableFrom(actualParamType)) {
                ScriptErrorReporter.report("${method.name} 注解事件类型 ${expectedEventType.simpleName} 与方法参数类型 ${actualParamType.simpleName} 不兼容，已忽略")
                continue
            }

            // 注册到全局注册表
            SubscriberRegistry.register(
                eventType = annotation.event,
                target = target,
                handler = SubscriberRegistry.Handler(instance, method)
            )
        }
    }
}
