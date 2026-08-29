package com.earthforge.klaymore;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.FMLCommonHandler;

import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import net.minecraftforge.common.MinecraftForge;

import java.util.function.Function;

import com.earthforge.klaymore.event.ForgeEventHandler;
import com.earthforge.klaymore.script.BuiltinTargetExtractors;
import com.earthforge.klaymore.script.EventTargetRegistrar;
import com.earthforge.klaymore.script.KotlinPreloader;
import com.earthforge.klaymore.script.PersistenceStorage;
import com.earthforge.klaymore.script.SubscriberRegistry;

@Mod(modid = Klaymore.MODID, version = Tags.VERSION, name = "MyMod", acceptedMinecraftVersions = "[1.7.10]")
public class Klaymore {

    public static final String MODID = "klaymore";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "com.earthforge.klaymore.ClientProxy", serverSide = "com.earthforge.klaymore.CommonProxy")
    public static CommonProxy proxy;

    // ================================================================
    // 【对外公开 API · 给其他 Mod 调用】
    // ================================================================
    // 其他 Mod 只要依赖 Klaymore，直接调用这些静态方法即可，不需要知道内部实现。
    // 全部是纯 Java 方法签名，preInit 早期调用也不会触发 Kotlin 类加载问题。

    /**
     * 派发任意事件给 Klaymore 脚本层（自动查找提取器 + 定向派发）。
     * <p>
     * 用法（其他 Mod 里）：
     * <pre>{@code
     *   MyCustomEvent event = new MyCustomEvent(player, data);
     *   boolean handled = Klaymore.postScriptEvent(event);
     * }</pre>
     * <p>
     * 派发逻辑完全和 Forge 内置事件一致：
     *   - 已注册提取器 → 提取目标 → 定向派发（有提取器绝不会全广播）
     *   - 未注册提取器 → 全广播（首次会打 warn，可 registerTargetExtractor 消除）
     *   - 事件类型如果被 markAsSkippedEvent → 直接 false return，完全不进入脚本层
     *
     * @param event 任意事件对象（继承 cpw.mods.fml.common.eventhandler.Event，或纯 POJO 均可）
     * @return true 表示至少有一个脚本处理器被调用
     */
    public static boolean postScriptEvent(Object event) {
        if (event == null) return false;
        try {
            return SubscriberRegistry.dispatch(event);
        } catch (Throwable t) {
            String eventName = event.getClass().getSimpleName();
            String errName = t.getClass().getSimpleName();
            LOG.error("[Klaymore] API postScriptEvent failed (" + eventName + "): "
                + t.getMessage() + " (api-called from another mod)", t);
            return false;
        }
    }

    /**
     * 派发事件给 Klaymore 脚本层，并显式指定绑定目标（跳过提取器查找）。
     * <p>
     * 用法（其他 Mod 里）：
     * <pre>{@code
     *   MyEvent evt = new MyEvent(player, someData);
     *   Klaymore.postScriptEvent(evt, player);   // 只派发给绑定了这个 player 的脚本
     * }</pre>
     *
     * @param event 任意事件对象
     * @param target 绑定目标（脚本 subscribe 时通过 register() 绑定的那个对象，例如玩家/方块坐标）
     * @return true 表示至少有一个脚本处理器被调用
     */
    public static boolean postScriptEvent(Object event, Object target) {
        if (event == null || target == null) return false;
        KotlinPreloader.preload();
        try {
            return SubscriberRegistry.dispatch(event, target);
        } catch (Throwable t) {
            String eventName = event.getClass().getSimpleName();
            LOG.error("[Klaymore] API postScriptEvent(target) failed (" + eventName + "): "
                + t.getMessage(), t);
            return false;
        }
    }

    /**
     * 注册事件目标提取器（给其他 Mod 的自定义事件类型用）。
     * <p>
     * 用法（其他 Mod 里，推荐在 preInit 阶段调用）：
     * <pre>{@code
     *   Klaymore.registerTargetExtractor(MyQuestEvent.class, e -> e.player);
     *   // 多目标：返回 List / 数组，会派发到多个目标，自动去重同一个 handler 调用
     *   Klaymore.registerTargetExtractor(MyTradeEvent.class, e -> {
     *       return Arrays.asList(e.player, e.villager);
     *   });
     * }</pre>
     * <p>
     * 注：继承链查找是自动的 —— 注册了 EntityEvent.class 的提取器，
     * EntityJoinWorldEvent 等子类会自动命中（除非子类有更精确的提取器注册）。
     *
     * @param eventClass 事件类型
     * @param extractor  事件 → 目标对象（或目标集合/数组）的提取函数。返回 null 表示本次无有效目标。
     * @param <T> 事件类型，必须继承 cpw.mods.fml.common.eventhandler.Event
     */
    public static <T extends Event> void registerTargetExtractor(
            Class<T> eventClass,
            Function<T, ?> extractor) {
        if (eventClass == null || extractor == null) return;
        EventTargetRegistrar.registerExtractor(eventClass, extractor);
    }

    /**
     * 标记某类事件为"无目标事件"：没有提取器也会全广播，但不会重复打 warn 刷屏。
     * <p>
     * 例：{@code Klaymore.markNoTargetEvent(ServerStartedEvent.class);}
     */
    public static <T extends Event> void markNoTargetEvent(Class<T> eventClass) {
        if (eventClass == null) return;
        EventTargetRegistrar.markAsNoTargetEvent(eventClass);
    }

    /**
     * 标记某类事件"完全跳过"：Klaymore 脚本层完全不会收到这个事件（也不会走提取器）。
     * 常用于高频 tick 事件防止掉帧。
     */
    public static <T extends Event> void markSkippedEvent(Class<T> eventClass) {
        if (eventClass == null) return;
        EventTargetRegistrar.markAsSkippedEvent(eventClass);
    }

    // ================================================================
    // Mod 生命周期
    // ================================================================

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // =================================================================
        // ⭐ 阶段 0：【纯 Java】Kotlin 运行时预热（双重保险 · 第一重）
        // =================================================================
        // 这一步必须是 preInit 里的第一行实际代码。
        // 用纯 Java Class.forName(initialize=true) 把 Intrinsics/Function1 等
        // 40+ 个 Kotlin 运行时关键类强制装载，尽可能让 parent ClassLoader 缓存住，
        // 避免 1.7.10 LaunchClassLoader 在早期 findClass 里的 NPE。
        KotlinPreloader.preload();

        // -----------------------------------------------------------------
        // 【下面阶段 1 ~ 4 全部是纯 Java 代码，完全不调用任何 Kotlin 方法】
        // 这是根治类加载问题的关键 —— 我们不再依赖 preInit 早期 Kotlin 能正常跑。
        // -----------------------------------------------------------------

        proxy.preInit(event);

        // =================================================================
        // ⭐ 阶段 1：注册 PersistenceStorage 事件总线
        // =================================================================
        try {
            PersistenceStorage.initialize();
            LOG.info("[Klaymore] PersistenceStorage event bus listener registered");
        } catch (Throwable t) {
            LOG.warn("[Klaymore] PersistenceStorage init had issues (non-fatal): " + t.getMessage());
        }

        // =================================================================
        // ⭐ 阶段 2：注册事件目标提取器（写入纯 Java 的 EventTargetRegistrar）
        // =================================================================
        try {
            BuiltinTargetExtractors.registerAll();
        } catch (Throwable t) {
            LOG.error("[Klaymore] Failed to register target extractors: " + t.getMessage(), t);
        }

        // =================================================================
        // ⭐ 阶段 3：注册 Forge 事件桥接（高性能精确监听 · 纯 Java 查询 Registrar）
        // =================================================================
        try {
            EventBus forgeBus = MinecraftForge.EVENT_BUS;
            EventBus fmlBus = FMLCommonHandler.instance().bus();
            int registered = 0;
            // 注意：getManagedEventClasses 来自纯 Java 的 EventTargetRegistrar，
            // 不是 SubscriberRegistry（Kotlin）。这一阶段仍然完全不进入 Kotlin。
            for (Class<?> eventCls : EventTargetRegistrar.getManagedEventClasses()) {
                if (Event.class.isAssignableFrom(eventCls)) {
                    ForgeEventHandler.registerBridgeFor(eventCls, forgeBus, fmlBus);
                    registered++;
                }
            }
            LOG.info("[Klaymore] Typed event bridges registered: " + registered + " classes "
                + "(RenderTickEvent/ClientTickEvent and other skipped events are NOT registered, "
                + "so they will never reach our code — no more frame drops from catch-all listener)");
        } catch (Throwable t) {
            LOG.error("[Klaymore] Failed to register typed event bridges: " + t.getMessage(), t);
        }

        // =================================================================
        // ⭐ 阶段 4（最后一行）：Kotlin 侧预热（双重保险 · 第二重）
        // =================================================================
        // 走到这里，preInit 所有 Java 工作都已做完，LaunchClassLoader 初始化也更成熟，
        // 此时再进入第一个 Kotlin 方法（SubscriberRegistry.preloadKotlinStdlib）就更稳了。
        // 即使这个 Kotlin 预热失败，我们也只是记一条 warning，不影响后续；
        // 真正的 Kotlin 调用会在 Forge 第一次派发事件时发生，那时 LaunchClassLoader
        // 已经完全就绪。
        try {
            SubscriberRegistry.preloadKotlinStdlib();
        } catch (Throwable t) {
            LOG.warn("[Klaymore] Kotlin-side preload warning (non-fatal, deferred to first event): "
                + t.getMessage());
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
        LOG.info("[Klaymore] Loading persisted script bindings...");
        try {
            PersistenceStorage.loadAll();
            LOG.info("[Klaymore] Persisted bindings loaded successfully.");
        } catch (Throwable t) {
            LOG.error("[Klaymore] Failed to load persisted bindings: " + t.getMessage(), t);
        }
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        LOG.info("[Klaymore] Saving script bindings to disk...");
        try {
            PersistenceStorage.saveAll();
            LOG.info("[Klaymore] Script bindings saved successfully.");
        } catch (Throwable t) {
            LOG.error("[Klaymore] Failed to save script bindings: " + t.getMessage(), t);
        }
    }
}
