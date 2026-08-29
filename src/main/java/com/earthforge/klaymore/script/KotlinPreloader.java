package com.earthforge.klaymore.script;

/**
 * ================================================================
 * 100% 纯 Java 的 Kotlin 运行时预加载器。
 *
 * 为什么需要这个？
 * 1.7.10 Forge 用 LaunchClassLoader 加载 Mod 类。在 preInit 早期，
 * LaunchClassLoader 内部的 resourceCache / classLoader 字段还没有完全初始化，
 * 它的 findClass() 方法会在第 182 行直接抛出 NullPointerException，被外层
 * 包装为 ClassNotFoundException:
 *   kotlin/jvm/internal/Intrinsics
 *   kotlin/jvm/functions/Function1
 *   等 kotlin.* 类
 *
 * 最坑的是：「在 Kotlin 方法里预热 Kotlin 运行时」这条路走不通 ——
 * 因为 Kotlin 编译器在每个 Kotlin 方法最开头都会注入 Intrinsics 的静态
 * 调用（参数 null 检查），所以只要你 JVM 准备执行第一个 Kotlin 方法的
 * 第 0 条指令，它就必须先解析 Intrinsics，而此时 LaunchClassLoader 还没
 * 准备好 → 依然 NPE。
 *
 * 所以：预热动作必须发生在调用「任何 Kotlin 方法之前」，而且执行预热的
 * 代码本身必须是纯 Java（不引入任何 kotlin.* 符号引用）。
 *
 * 原理：
 *   每个 Class 一旦被「任意父级/上下文类加载器」成功 defineClass，
 *   LaunchClassLoader 会通过 parent-delegation 先在父加载器里找这个
 *   类，直接命中已缓存的结果，根本不会走它自己那个会 NPE 的 findClass。
 *   本类用本类自己的 ClassLoader（通常是 AppClassLoader / LaunchClassLoader 的父）
 *   去 Class.forName(initialize=true) 把 Kotlin 运行时里所有 1.7.10 会用到
 *   的类全部加载初始化一遍。这样后续所有 Kotlin 调用都会安全命中缓存。
 *
 * 调用位置：Klaymore.preInit() 第一行，甚至在 proxy.preInit(event) 之前。
 * ================================================================
 */
public final class KotlinPreloader {

    private KotlinPreloader() {}

    /** 预热是否已经跑过一次，避免重复开销（虽然重复跑是安全的，但没必要） */
    private static volatile boolean sPreloaded = false;

    /**
     * Shadow relocate 前缀 —— 必须与 build.gradle.kts 和 script-runtime/build.gradle
     * 中的 relocate 规则完全一致：
     *   relocate("kotlin", "com.earthforge.klaymore.shadow.kotlin")
     *   即所有 kotlin.* 类运行时都在 com.earthforge.klaymore.shadow. 前缀下。
     */
    private static final String SHADOW_PREFIX = "com.earthforge.klaymore.shadow.";

    /**
     * 1.7.10 + Kotlin 开发 Mod 时，preInit 开头最容易缺的 Kotlin 运行时关键类。
     * 这里保留「原始包名」，preload() 方法会自动为每个类生成 SHADOW_PREFIX 前缀的版本尝试加载。
     * 顺序：先 load 最底层的（Intrinsics → Function 系列 → 基础类型 → 标准库常用类）。
     * 缺了哪个类以后在报错里看到了，直接加到这里即可。
     */
    private static final String[] KOTLIN_REQUIRED_CLASSES = new String[] {
        // ---- kotlin.jvm.internal（Kotlin 编译器硬引用的，最最关键）----
        "kotlin.jvm.internal.Intrinsics",
        "kotlin.jvm.internal.Intrinsics$WhenMappings",
        "kotlin.jvm.internal.ClassReference",
        "kotlin.jvm.internal.FunctionReference",
        "kotlin.jvm.internal.CallableReference",
        "kotlin.jvm.internal.PropertyReference0Impl",
        "kotlin.jvm.internal.PropertyReference1Impl",
        "kotlin.jvm.internal.MutablePropertyReference0Impl",
        "kotlin.jvm.internal.MutablePropertyReference1Impl",
        "kotlin.jvm.internal.Lambda",
        "kotlin.jvm.internal.Reflection",
        "kotlin.jvm.internal.ReflectionFactory",

        // ---- kotlin.jvm.functions（Kotlin 函数类型的 SAM 接口）----
        "kotlin.jvm.functions.Function0",
        "kotlin.jvm.functions.Function1",
        "kotlin.jvm.functions.Function2",
        "kotlin.jvm.functions.Function3",
        "kotlin.jvm.functions.Function4",
        "kotlin.jvm.functions.Function5",

        // ---- kotlin / kotlin.jvm 基础类型 ----
        "kotlin.Unit",
        "kotlin.Nothing",
        "kotlin.Any",
        "kotlin.Lazy",
        "kotlin.LazyKt",
        "kotlin.LazyJVMKt",
        "kotlin.Pair",
        "kotlin.Triple",
        "kotlin.jvm.JvmClassMappingKt",
        "kotlin.jvm.JvmStatic",
        "kotlin.jvm.JvmOverloads",
        "kotlin.jvm.JvmField",

        // ---- kotlin.collections（最常用的集合，SubscriberRegistry 等会用到）----
        "kotlin.collections.CollectionsKt",
        "kotlin.collections.CollectionsKt__CollectionsKt",
        "kotlin.collections.CollectionsKt__IterablesKt",
        "kotlin.collections.CollectionsKt__MutableCollectionsKt",
        "kotlin.collections.MapsKt",
        "kotlin.collections.MapsKt__MapsKt",
        "kotlin.collections.SetsKt",
        "kotlin.collections.ArraysKt",
        "kotlin.ranges.RangesKt",
        "kotlin.ranges.IntRange",

        // ---- kotlin.jvm.internal.markers（有些 KClass 强转用）----
        "kotlin.jvm.internal.markers.KMappedMarker",

        // ---- kotlin.reflect（KClass 引用会用到）----
        "kotlin.KClass",
        "kotlin.jvm.internal.ClassBasedDeclarationContainer",
        "kotlin.jvm.internal.KClassImpl",
        "kotlin.jvm.internal.KotlinReflectionInternalError",

        // ---- 额外的 shadow 后也可能直接引用 kotlinx / org.jetbrains 下的类 ----
        "kotlinx.coroutines.Job",
        "kotlinx.coroutines.CoroutineScope",
        "org.jetbrains.annotations.Nullable",
        "org.jetbrains.annotations.NotNull"
    };

    /**
     * 尝试加载单个类，失败不抛出（静默）。
     * 优先加载「shadow 后版本」（= klaymore-runtime.jar 中实际存在的），
     * 再 fallback 加载「原始版本」（IDE 直接跑 class 文件时可能用到）。
     */
    private static boolean tryLoadClass(ClassLoader loader, String rawName) {
        String shadowName = SHADOW_PREFIX + rawName;
        try {
            Class.forName(shadowName, true, loader);
            return true;
        } catch (Throwable ignored) {
        }
        try {
            Class.forName(rawName, true, loader);
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 执行预热。整个方法是纯 Java：没有任何 kotlin.* 的 import 或符号引用。
     * 可重复调用（第 2 次起直接返回）。
     *
     * 关键：对每个原始类名都先尝试加载 SHADOW_PREFIX 前缀的 relocate 后版本，
     * 因为主模块 shadowJar 已经把所有 Kotlin 字节码引用重写为 shadow 包名了。
     *
     * @return true 表示所有类均成功加载；false 表示部分失败（非致命，日志里有详情）
     */
    @SuppressWarnings("UseOfSystemOutOrSystemErr")
    public static boolean preload() {
        if (sPreloaded) return true;
        synchronized (KotlinPreloader.class) {
            if (sPreloaded) return true;
            ClassLoader ourLoader = KotlinPreloader.class.getClassLoader();
            int successCount = 0;
            int failCount = 0;
            String firstFailure = null;
            for (String rawName : KOTLIN_REQUIRED_CLASSES) {
                if (tryLoadClass(ourLoader, rawName)) {
                    successCount++;
                } else {
                    failCount++;
                    if (firstFailure == null) {
                        firstFailure = SHADOW_PREFIX + rawName;
                    }
                    if (failCount == 1) {
                        System.err.println("[Klaymore] KotlinPreloader: class not found on classpath (is klaymore-runtime.jar present?): "
                            + SHADOW_PREFIX + rawName);
                    }
                }
            }
            sPreloaded = true;
            boolean allOk = failCount == 0;
            if (allOk) {
                System.out.println("[Klaymore] KotlinPreloader OK: " + successCount
                    + " shadowed Kotlin runtime classes preloaded (pure-Java warm-up path)");
            } else {
                System.out.println("[Klaymore] KotlinPreloader PARTIAL: "
                    + successCount + " loaded, " + failCount
                    + " missing. First missing class was: "
                    + (firstFailure == null ? "" : firstFailure)
                    + " → please ensure klaymore-runtime.jar is placed in mods/ folder alongside Klaymore jar.");
            }
            return allOk;
        }
    }
}
