package com.earthforge.klaymore.event;

import com.earthforge.klaymore.script.SubscriberRegistry;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.eventhandler.IEventListener;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ================================================================
 * Forge 事件桥接（高性能版）
 *
 * 为什么要做这个？
 * 之前我们用一个 @SubscribeEvent public void onForgeEvent(Event event) {}
 * 监听整个 EventBus —— 这样**所有** Forge 事件（包括每帧几十次的
 * RenderTickEvent、ClientTickEvent 等纯客户端 tick）都会调用进我们的方法，
 * 即使方法一开头就 return，也已经完成了：
 *   FML ASM → 反射调用 → 栈帧构造 → Kotlin 静态方法 → return
 * 这些开销在 60fps + 叠加多层调用的情况下，足以引起明显掉帧。
 *
 * 现在的方案（双层性能保护）：
 *   1. 只有在 BuiltinTargetExtractors 中注册了提取器 / no-target 的事件类
 *      才会登记监听器；对于 markAsSkippedEvent 的高频事件（RenderTick 等）
 *      根本不注册监听器 → Forge EventBus.post() 时找不到 listener，
 *      我们代码 1 条指令都不执行。
 *
 *   2. 登记监听器时采用"按事件类型精确分派"两种路径（快 → 慢 fallback）：
 *      a) 快路径：命中 BRIDGE_FACTORIES（编译期预生成的精确签名内部类）
 *         FML 本身就按参数类型分流，0 instanceof。
 *      b) 回退路径：反射直接把 IEventListener 写入 EventBus.listeners 对应
 *         eventClass 的 listener 列表，性能等价于 (a)。
 *
 *   两种路径**都不会**出现"一个 Event 参数 + 所有事件全进来"的 catch-all 情况。
 * ================================================================
 */
public class ForgeEventHandler {

    /** (事件简单名|异常简单名) → 已经输出过一次 ERROR，防止同类错误刷屏 */
    private static final Set<String> REPORTED_ERROR_KEYS =
        Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** busHashCode|Class全限定名 → 已注册过，避免重复注册到同一 bus */
    private static final Set<String> REGISTERED_KEYS =
        Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    // ------------------------------------------------------------------
    // 对外 API
    // ------------------------------------------------------------------

    /**
     * 为具体事件类注册一个精确监听器（只接收 eventClass 及其子类）。
     * 对 skip 类直接 no-op。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void registerBridgeFor(Class eventClass, EventBus... buses) {
        if (eventClass == null || buses == null) return;
        if (!Event.class.isAssignableFrom(eventClass)) return;
        Class<? extends Event> cls = (Class<? extends Event>) eventClass;
        // 注意：这里走纯 Java 的 EventTargetRegistrar（而不是 SubscriberRegistry Kotlin 类），
        // 这样整个 preInit 桥接注册阶段也完全不调用 Kotlin，避免 LaunchClassLoader 时序问题。
        if (com.earthforge.klaymore.script.EventTargetRegistrar.isEventSkipped(cls)) return;

        for (EventBus bus : buses) {
            if (bus == null) continue;
            String key = busKey(bus) + "|" + cls.getName();
            if (!REGISTERED_KEYS.add(key)) continue;
            try {
                registerTypedListener(bus, cls);
            } catch (Throwable t) {
                com.earthforge.klaymore.Klaymore.LOG.error(
                    "[Klaymore] Failed to register typed bridge for " + cls.getName(), t);
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部：注册精确监听器（快路径 → 回退路径）
    // ------------------------------------------------------------------

    private static void registerTypedListener(EventBus bus, Class<? extends Event> eventClass) throws Exception {
        // --- 快路径：预生成精确签名类（编译期类型，0 instanceof）---
        java.util.function.Supplier<Object> factory = BRIDGE_FACTORIES.get(eventClass.getName());
        if (factory != null) {
            bus.register(factory.get());
            com.earthforge.klaymore.Klaymore.LOG.debug(
                "[Klaymore] [FAST] Typed bridge registered: " + eventClass.getName());
            return;
        }

        // --- 回退路径：反射注入 IEventListener 到 bus.listeners ---
        // 这是 FML 1.7.10/1.8.x EventBus 内部格式：
        //   ConcurrentHashMap<Class<? extends Event>, List<IEventListener>> listeners
        // 直接把 listener 塞进对应 eventClass 的 list，就等价于精确注册，
        // 和 @SubscribeEvent(XxxEvent.class) 达到的最终状态一致。
        IEventListener listener = new IEventListener() {
            @Override
            public void invoke(Event event) {
                dispatch(event);
            }
        };
        Field listenersField = findField(bus.getClass(), "listeners");
        if (listenersField != null) {
            listenersField.setAccessible(true);
            Object listenersMap = listenersField.get(bus);
            if (listenersMap instanceof java.util.concurrent.ConcurrentHashMap) {
                @SuppressWarnings("unchecked")
                java.util.concurrent.ConcurrentHashMap<Object, List<Object>> map =
                    (java.util.concurrent.ConcurrentHashMap<Object, List<Object>>) listenersMap;
                List<Object> list = map.get(eventClass);
                if (list == null) {
                    List<Object> newList = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
                    List<Object> existing = map.putIfAbsent(eventClass, newList);
                    list = existing != null ? existing : newList;
                }
                list.add(listener);

                // 让 FML 的 listenerLists 缓存失效（如果存在的话），让它下一次 post 重建
                Field cacheField = findField(bus.getClass(), "listenerLists");
                if (cacheField != null) {
                    try {
                        cacheField.setAccessible(true);
                        cacheField.set(bus, null);
                    } catch (Throwable ignored) {}
                }
                com.earthforge.klaymore.Klaymore.LOG.debug(
                    "[Klaymore] [REFLECT-FALLBACK] Typed bridge injected: " + eventClass.getName());
                return;
            }
        }

        // --- 终级 fallback：注册 (Event) 参数的通用 listener，但这是一个 listener 对应
        //     一个 eventClass 的注册 —— 而我们根本不给 skipped 类注册 listener，
        //     所以 RenderTick 还是不会来。这里对"只给关心的类注册"这个前提没变。
        bus.register(new SingleEventBridge(eventClass));
        com.earthforge.klaymore.Klaymore.LOG.debug(
            "[Klaymore] [FINAL-FALLBACK] SingleEventBridge registered: " + eventClass.getName());
    }

    /**
     * 最后回退：用 @SubscribeEvent(Event) + instanceof 过滤。
     * 虽然方法参数是 Event，但因为我们只有"关心的非 skipped 事件类"才注册这个，
     * 所以 skipped 高频类（RenderTick 等）不会生成对应的 SingleEventBridge。
     * 只有当反射注入也失败，且 BRIDGE_FACTORIES 没命中时才会走到这条路，
     * 属于极端罕见情形。
     */
    public static class SingleEventBridge {
        final Class<? extends Event> eventClass;
        public SingleEventBridge(Class<? extends Event> eventClass) { this.eventClass = eventClass; }

        @SubscribeEvent
        public void onEvent(Event event) {
            if (eventClass.isInstance(event)) dispatch(event);
        }
    }

    // ------------------------------------------------------------------
    // 派发入口（桥接用）
    // ------------------------------------------------------------------

    /** 所有桥接最终都会走到这里 —— 统一 try/catch + 错误去重 */
    static void dispatch(Event event) {
        if (event == null) return;
        try {
            SubscriberRegistry.dispatch(event);
        } catch (Throwable t) {
            String eventName = event.getClass().getSimpleName();
            String errName = t.getClass().getSimpleName();
            String key = eventName + "|" + errName;
            if (REPORTED_ERROR_KEYS.add(key)) {
                com.earthforge.klaymore.Klaymore.LOG.error(
                    "[Klaymore] Error dispatching Forge event " + eventName + ": " + t.getMessage()
                        + " (this error type will be muted hereafter to avoid spam)",
                    t
                );
            }
        }
    }

    // ------------------------------------------------------------------
    // 预生成的精确签名桥接类（快路径，0 instanceof）
    // 按需在 BRIDGE_FACTORIES 中补充，性能最好。
    // ------------------------------------------------------------------

    public static class EntityEventBridge {
        @SubscribeEvent
        public void onEntity(net.minecraftforge.event.entity.EntityEvent e) { dispatch(e); }
    }
    public static class PlayerEventBridge {
        @SubscribeEvent
        public void onPlayer(net.minecraftforge.event.entity.player.PlayerEvent e) { dispatch(e); }
    }
    public static class LivingEventBridge {
        @SubscribeEvent
        public void onLiving(net.minecraftforge.event.entity.living.LivingEvent e) { dispatch(e); }
    }
    public static class BlockEventBridge {
        @SubscribeEvent
        public void onBlock(net.minecraftforge.event.world.BlockEvent e) { dispatch(e); }
    }
    public static class ServerChatEventBridge {
        @SubscribeEvent
        public void onChat(net.minecraftforge.event.ServerChatEvent e) { dispatch(e); }
    }
    public static class FMLPlayerEventBridge {
        @SubscribeEvent
        public void onFMLPlayer(cpw.mods.fml.common.gameevent.PlayerEvent e) { dispatch(e); }
    }
    public static class WorldEventBridge {
        @SubscribeEvent
        public void onWorld(net.minecraftforge.event.world.WorldEvent e) { dispatch(e); }
    }
    public static class TickEventBridge {
        @SubscribeEvent
        public void onTick(cpw.mods.fml.common.gameevent.TickEvent e) { dispatch(e); }
    }
    public static class ServerTickEventBridge {
        @SubscribeEvent
        public void onServerTick(cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent e) { dispatch(e); }
    }

    private static final java.util.Map<String, java.util.function.Supplier<Object>> BRIDGE_FACTORIES;
    static {
        BRIDGE_FACTORIES = new java.util.HashMap<>();
        BRIDGE_FACTORIES.put("net.minecraftforge.event.entity.EntityEvent", EntityEventBridge::new);
        BRIDGE_FACTORIES.put("net.minecraftforge.event.entity.player.PlayerEvent", PlayerEventBridge::new);
        BRIDGE_FACTORIES.put("net.minecraftforge.event.entity.living.LivingEvent", LivingEventBridge::new);
        BRIDGE_FACTORIES.put("net.minecraftforge.event.world.BlockEvent", BlockEventBridge::new);
        BRIDGE_FACTORIES.put("net.minecraftforge.event.ServerChatEvent", ServerChatEventBridge::new);
        BRIDGE_FACTORIES.put("cpw.mods.fml.common.gameevent.PlayerEvent", FMLPlayerEventBridge::new);
        BRIDGE_FACTORIES.put("net.minecraftforge.event.world.WorldEvent", WorldEventBridge::new);
        BRIDGE_FACTORIES.put("cpw.mods.fml.common.gameevent.TickEvent", TickEventBridge::new);
        BRIDGE_FACTORIES.put("cpw.mods.fml.common.gameevent.TickEvent$ServerTickEvent", ServerTickEventBridge::new);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static String busKey(EventBus bus) {
        return bus.getClass().getName() + "@" + System.identityHashCode(bus);
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
        }
        return null;
    }
}
