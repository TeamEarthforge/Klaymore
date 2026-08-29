package com.earthforge.klaymore.script;

import cpw.mods.fml.common.eventhandler.Event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * ================================================================
 * 【事件目标注册表 · 纯 Java 版】
 *
 * 这是整个"提取器/跳过/no-target"配置的**唯一真源（Single Source of Truth）**，
 * 100% 纯 Java 实现：0 kotlin.* import、字节码层面 0 个 kotlin 符号引用。
 *
 * 为什么要这样设计？
 *   1.7.10 的 LaunchClassLoader 在 preInit 早期有严重的类加载时序问题：
 *   它的 findClass() 方法在初始化未完成时会直接抛 NPE（堆栈行号 101 或 182），
 *   被外层包装为 ClassNotFoundException:
 *     - kotlin/jvm/internal/Intrinsics
 *     - kotlin/jvm/functions/Function1
 *     - 等等
 *   之前所有"用 Kotlin 方法注册提取器"的方案都会在 preInit 早期第一次
 *   调用 Kotlin 方法时触发此问题 —— 因为 Kotlin 编译器在每个 Kotlin 方法
 *   开头都会注入 Intrinsics 静态调用，JVM 要解析这个符号才能进入方法体，
 *   这就要求 LaunchClassLoader 已经能加载 Kotlin 类，但 preInit 早期它还不行。
 *
 * 解决方案（本类存在的意义）：
 *   把 preInit 阶段需要读写的所有状态（extractors/noTarget/skipped 三张表）
 *   全部放到这个纯 Java 类里。整个 preInit 流程：
 *     KotlinPreloader.preload() → 提取器注册 → 桥接监听器注册
 *   全链路**不调用任何 Kotlin 代码**。
 *
 *   等 Forge 真的派发第一个事件时（此时 LaunchClassLoader 早已初始化完毕），
 *   SubscriberRegistry.dispatch()（Kotlin 方法）才会第一次被调用，那时
 *   Intrinsics 类加载就 100% 安全了。
 * ================================================================
 */
public final class EventTargetRegistrar {

    private EventTargetRegistrar() {}

    // ==================================================================
    // 内部存储（全部 Concurrent 安全，EventBus 多线程派发也没问题）
    // ==================================================================

    /**
     * 事件类 → 目标提取器。
     * 注意：value 是纯 Java 的 {@link Function}（返回 null/单对象/List/数组皆可），
     * 只有 Kotlin 侧真正调用时才会懒包装为 Kotlin Function1。
     */
    private static final Map<Class<?>, Function<Object, ?>> EXTRACTORS = new ConcurrentHashMap<>();

    /** 已知无目标的事件类（未命中提取器时不输出 WARN，全广播） */
    private static final Set<Class<?>> NO_TARGET = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** 完全跳过的事件类（监听器都不注册，dispatch 入口直接短路） */
    private static final Set<Class<?>> SKIPPED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * findExtractor 沿继承链查找时的缓存：(具体事件类) → (找到的提取器或 null)。
     * 高频事件（EntityJoinWorldEvent、LivingUpdateEvent 等）每 tick 都有，
     * 缓存后每次派发 O(1) 命中，不用每次沿继承链遍历。
     */
    private static final Map<Class<?>, Function<Object, ?>> EXTRACTOR_CACHE = new ConcurrentHashMap<>();

    private static final Map<Class<?>, Boolean> NO_TARGET_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> SKIPPED_CACHE = new ConcurrentHashMap<>();

    // ==================================================================
    // 注册 API（供 BuiltinTargetExtractors / 你的自定义注册代码调用）
    // ==================================================================

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T extends Event> void registerExtractor(
        Class<T> eventClass,
        Function<T, ?> extractor
    ) {
        if (eventClass == null || extractor == null) return;
        Function<Object, ?> previous;
        synchronized (EXTRACTORS) {
            previous = EXTRACTORS.put(eventClass, (Function<Object, ?>) (Function) extractor);
            // 写入新值后让继承链缓存失效（否则之前缓存过 null 的子类会看不到新注册的父类提取器）
            EXTRACTOR_CACHE.clear();
        }
        if (previous != null) {
            // 这里用 System.out 而不是 LOG —— 注册发生在 preInit 早期，
            // LOG 本身可能触发其他初始化，尽量保持纯。
            System.out.println("[Klaymore] Registrar WARNING: overwriting extractor for "
                + eventClass.getSimpleName());
        }
        System.out.println("[Klaymore] Registrar: extractor registered for "
            + eventClass.getSimpleName());
    }

    public static void markAsNoTargetEvent(Class<? extends Event> eventClass) {
        if (eventClass == null) return;
        NO_TARGET.add(eventClass);
        NO_TARGET_CACHE.clear();
        System.out.println("[Klaymore] Registrar: marked as no-target (broadcast) — "
            + eventClass.getSimpleName());
    }

    public static void markAsSkippedEvent(Class<? extends Event> eventClass) {
        if (eventClass == null) return;
        SKIPPED.add(eventClass);
        SKIPPED_CACHE.clear();
        System.out.println("[Klaymore] Registrar: marked as SKIPPED (no dispatch, no listener) — "
            + eventClass.getSimpleName());
    }

    // ==================================================================
    // 查询 API（供 SubscriberRegistry.kt / ForgeEventHandler 调用）
    // 沿继承链查找 + 缓存，高频事件零遍历。
    // ==================================================================

    /**
     * 沿继承链查找该事件类对应的提取器（子类优先于父类）。
     * 找不到返回 null。Kotlin 层拿到 Function 后自行包装为 Kotlin Function1 调用。
     */
    public static Function<Object, ?> findExtractor(Class<?> eventClass) {
        if (eventClass == null) return null;
        Function<Object, ?> cached = EXTRACTOR_CACHE.get(eventClass);
        if (cached != null || EXTRACTOR_CACHE.containsKey(eventClass)) {
            // 第二个条件命中 null 缓存（"查过了，确实没有提取器"）
            return cached;
        }
        Function<Object, ?> found = findExtractorNoCache(eventClass);
        EXTRACTOR_CACHE.put(eventClass, found); // 即使 found==null 也缓存，避免反复查
        return found;
    }

    private static Function<Object, ?> findExtractorNoCache(Class<?> eventClass) {
        Class<?> current = eventClass;
        while (current != null) {
            Function<Object, ?> hit = EXTRACTORS.get(current);
            if (hit != null) return hit;
            for (Class<?> iface : current.getInterfaces()) {
                hit = EXTRACTORS.get(iface);
                if (hit != null) return hit;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    public static boolean isKnownNoTargetEvent(Class<?> eventClass) {
        if (eventClass == null) return false;
        Boolean cached = NO_TARGET_CACHE.get(eventClass);
        if (cached != null) return cached;
        boolean found = testHierarchy(eventClass, NO_TARGET);
        NO_TARGET_CACHE.put(eventClass, found);
        return found;
    }

    public static boolean isEventSkipped(Class<?> eventClass) {
        if (eventClass == null) return false;
        Boolean cached = SKIPPED_CACHE.get(eventClass);
        if (cached != null) return cached;
        boolean found = testHierarchy(eventClass, SKIPPED);
        SKIPPED_CACHE.put(eventClass, found);
        return found;
    }

    private static boolean testHierarchy(Class<?> eventClass, Set<Class<?>> table) {
        Class<?> current = eventClass;
        while (current != null) {
            if (table.contains(current)) return true;
            for (Class<?> iface : current.getInterfaces()) {
                if (table.contains(iface)) return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    // ==================================================================
    // 元信息（供 Klaymore.preInit 注册桥接监听器时用）
    // ==================================================================

    /**
     * 返回所有被显式管理过的事件类（提取器注册过 + no-target 标记过 + skipped 标记过的并集）。
     * 遍历顺序 = 注册顺序。用于在 preInit 末段逐个注册精确类型监听器。
     */
    public static Set<Class<?>> getManagedEventClasses() {
        LinkedHashSet<Class<?>> out = new LinkedHashSet<>();
        out.addAll(EXTRACTORS.keySet());
        out.addAll(NO_TARGET);
        // skipped 类也放进去 —— ForgeEventHandler.registerBridgeFor 内部会再用
        // isEventSkipped 过滤掉，所以放进来没关系，方便 debug 时看完整清单。
        out.addAll(SKIPPED);
        return out;
    }

    // ==================================================================
    // 目标展开工具（把提取器的返回值展开为 List<Object>）
    //
    // 为什么放在这个纯 Java 类里？
    //   SubscriberRegistry.dispatch 调用 Kotlin 方法前，只要能在纯 Java 里
    //   把目标展开，就可以让 Kotlin 的 flattenTargets 实现也延迟调用。
    //   但更关键：这个工具类给 SubscriberRegistry 调用时无需二次兼容。
    // ==================================================================

    public static List<Object> flattenTargets(Object raw) {
        if (raw == null) return Collections.emptyList();
        if (raw instanceof Iterable) {
            LinkedHashSet<Object> set = new LinkedHashSet<>();
            for (Object o : (Iterable<?>) raw) {
                if (o != null) set.add(o);
            }
            return new java.util.ArrayList<>(set);
        }
        if (raw instanceof Object[]) {
            LinkedHashSet<Object> set = new LinkedHashSet<>();
            for (Object o : (Object[]) raw) {
                if (o != null) set.add(o);
            }
            return new java.util.ArrayList<>(set);
        }
        if (raw instanceof boolean[]) {
            boolean[] arr = (boolean[]) raw;
            java.util.ArrayList<Object> list = new java.util.ArrayList<>(arr.length);
            for (boolean b : arr) list.add(b);
            return list;
        }
        if (raw instanceof byte[]) {
            byte[] arr = (byte[]) raw;
            java.util.ArrayList<Object> list = new java.util.ArrayList<>(arr.length);
            for (byte b : arr) list.add(b);
            return list;
        }
        if (raw instanceof char[]) {
            char[] arr = (char[]) raw;
            java.util.ArrayList<Object> list = new java.util.ArrayList<>(arr.length);
            for (char c : arr) list.add(c);
            return list;
        }
        if (raw instanceof short[]) {
            short[] arr = (short[]) raw;
            java.util.ArrayList<Object> list = new java.util.ArrayList<>(arr.length);
            for (short s : arr) list.add(s);
            return list;
        }
        if (raw instanceof int[]) {
            int[] arr = (int[]) raw;
            java.util.ArrayList<Object> list = new java.util.ArrayList<>(arr.length);
            for (int i : arr) list.add(i);
            return list;
        }
        if (raw instanceof long[]) {
            long[] arr = (long[]) raw;
            java.util.ArrayList<Object> list = new java.util.ArrayList<>(arr.length);
            for (long l : arr) list.add(l);
            return list;
        }
        if (raw instanceof float[]) {
            float[] arr = (float[]) raw;
            java.util.ArrayList<Object> list = new java.util.ArrayList<>(arr.length);
            for (float f : arr) list.add(f);
            return list;
        }
        if (raw instanceof double[]) {
            double[] arr = (double[]) raw;
            java.util.ArrayList<Object> list = new java.util.ArrayList<>(arr.length);
            for (double d : arr) list.add(d);
            return list;
        }
        return Collections.singletonList(raw);
    }
}
