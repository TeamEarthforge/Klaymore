package com.earthforge.klaymore.script;

import java.util.function.Function;
import cpw.mods.fml.common.eventhandler.Event;

/**
 * 事件目标提取器注册表（公开 API，Java 友好）。
 *
 * <p>此类现在是一个薄包装，所有操作最终落到【纯 Java 的 EventTargetRegistrar】上，
 * 字节码中完全没有 kotlin.* 的符号引用，因此可以在 preInit 最早期安全调用
 * （不会触发 1.7.10 LaunchClassLoader 的类加载 NPE）。</p>
 *
 * <pre>{@code
 * // Java 示例：为自定义事件注册提取器
 * EventTargetExtractorRegistry.registerExtractor(
 *     MyCustomEvent.class,
 *     event -> event.getMyTargetEntity()
 * );
 * }</pre>
 */
public final class EventTargetExtractorRegistry {

    private EventTargetExtractorRegistry() {}

    /**
     * 为指定事件类型注册目标提取器。
     *
     * @param eventClass 事件类（必须是 cpw.mods.fml.common.eventhandler.Event 的子类）
     * @param extractor  提取函数。返回规则：
     *                   <ul>
     *                     <li>返回 null / 空集合 / 空数组 → 完全不派发（也不全广播）</li>
     *                     <li>返回单个对象 → 仅派发给绑定到该对象的脚本</li>
     *                     <li>返回 Iterable（List/Set 等）或数组 → 把每一项都当作候选目标派发，handler 自动去重</li>
     *                   </ul>
     * @param <T> 事件类型
     */
    public static <T extends Event> void registerExtractor(
            Class<T> eventClass,
            Function<T, ?> extractor
    ) {
        EventTargetRegistrar.registerExtractor(eventClass, extractor);
    }

    /**
     * 将指定事件类标记为"已知无目标事件"。这类事件在派发时若未找到提取器，将不会输出 WARN 日志，
     * 但仍会以全广播方式派发给所有订阅者。
     *
     * @param eventClass 事件类
     */
    public static void markAsNoTargetEvent(Class<? extends Event> eventClass) {
        EventTargetRegistrar.markAsNoTargetEvent(eventClass);
    }

    /**
     * 将指定事件类标记为"跳过事件"——这类事件**完全不派发给脚本**（不注册监听器、不进 dispatch、也不全广播）。
     *
     * 适合用在：
     * <ul>
     *   <li>纯客户端高频事件（RenderTickEvent、ClientTickEvent 等）</li>
     *   <li>脚本层根本不会用到的底层事件，屏蔽后可减少不必要的开销</li>
     *   <li>已知与 Kotlin 运行时类加载顺序冲突、调用就报错的事件</li>
     * </ul>
     *
     * 标记后：preInit 注册桥接监听器时会忽略这个类（EventBus 层不会派过来）；
     * 就算其他代码手动调用了 dispatch，入口也会短路直接返回 false。
     *
     * @param eventClass 事件类
     */
    public static void markAsSkippedEvent(Class<? extends Event> eventClass) {
        EventTargetRegistrar.markAsSkippedEvent(eventClass);
    }
}
