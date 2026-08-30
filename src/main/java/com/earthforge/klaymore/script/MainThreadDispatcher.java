package com.earthforge.klaymore.script;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.server.MinecraftServer;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * MainThreadDispatcher
 * --------------------
 * 把 Runnable 安全地调度回游戏主线程执行。
 *
 * 为什么不直接用 Minecraft / MinecraftServer 的内置调度？
 *   - 客户端：net.minecraft.client.Minecraft#func_152344_a 存在，但属于 net.minecraft.client.* 包，
 *     服务端加载时会触发 ClassNotFound。
 *   - 服务端：MinecraftServer#addScheduledTask / func_152358_ax 在不同 Forge 构建里名字不稳定。
 *   - 本类用反射 + TickEvent fallback 双保险，在 client / server / dev / prod 都能跑。
 *
 * 策略优先级：
 *   1. 尝试反射调用 MinecraftClient.schedule（如果当前是客户端环境且类已加载）
 *   2. 尝试反射调用 MinecraftServer.addScheduledTask（如果当前有服务器实例）
 *   3. Fallback：塞到 ConcurrentLinkedQueue，每次 ServerTickEvent / ClientTickEvent 时 drain 执行
 */
public final class MainThreadDispatcher {

    private static final Queue<Runnable> pendingQueue = new ConcurrentLinkedQueue<Runnable>();
    private static volatile boolean tickHookRegistered = false;

    private MainThreadDispatcher() {}

    /** 总是安全的：无论在哪侧、哪个线程调用都不会崩 */
    public static void schedule(Runnable task) {
        if (task == null) return;

        // ---- 捷径 0：已经在主线程（用 FML 的 side check + 线程名组合判断）----
        if (isLikelyMainThread()) {
            try {
                task.run();
                return;
            } catch (Throwable t) {
                t.printStackTrace();
                return;
            }
        }

        // ---- 1. 客户端：反射 Minecraft.func_152344_a ----
        boolean scheduledByClient = false;
        try {
            Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcCls.getMethod("getMinecraft").invoke(null);
            if (mc != null) {
                java.lang.reflect.Method schedule =
                    mcCls.getMethod("func_152344_a", Runnable.class);
                schedule.invoke(mc, wrapSafely(task));
                scheduledByClient = true;
            }
        } catch (Throwable ignored) { /* 不是客户端 / 方法不存在 */ }
        if (scheduledByClient) return;

        // ---- 2. 服务端：反射 MinecraftServer.func_152358_ax 的调度或 addScheduledTask ----
        boolean scheduledByServer = false;
        try {
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null) {
                // 尝试 addScheduledTask
                try {
                    java.lang.reflect.Method m =
                        server.getClass().getMethod("addScheduledTask", Runnable.class);
                    m.invoke(server, wrapSafely(task));
                    scheduledByServer = true;
                } catch (NoSuchMethodException ignored) {}
                if (!scheduledByServer) {
                    // 尝试 func_152358_ax → FutureTaskScheduler 之类的字段
                    try {
                        java.lang.reflect.Method m =
                            server.getClass().getMethod("func_152358_ax");
                        Object scheduler = m.invoke(server);
                        if (scheduler != null) {
                            java.lang.reflect.Method schedMethod =
                                scheduler.getClass().getMethod("schedule", Runnable.class, long.class,
                                    java.util.concurrent.TimeUnit.class);
                            schedMethod.invoke(scheduler, wrapSafely(task), 0L,
                                java.util.concurrent.TimeUnit.MILLISECONDS);
                            scheduledByServer = true;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) { /* 服务器没启动 */ }
        if (scheduledByServer) return;

        // ---- 3. Fallback：进队列，下一个 tick 执行 ----
        ensureTickHookRegistered();
        pendingQueue.add(wrapSafely(task));
    }

    private static Runnable wrapSafely(final Runnable inner) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    inner.run();
                } catch (Throwable t) {
                    System.err.println("[Klaymore MainThreadDispatcher] Exception in scheduled task: "
                        + t.getMessage());
                    t.printStackTrace(System.err);
                }
            }
        };
    }

    private static boolean isLikelyMainThread() {
        // 简单启发式：Forge 1.7.10 主线程名通常是 "main" (客户端) / "Server thread" (服务端)
        String name = Thread.currentThread().getName();
        if ("main".equals(name) || "Server thread".equals(name)) return true;
        // 客户端在某些启动阶段可能叫 "Minecraft main thread"
        if (name != null && name.contains("main") && name.toLowerCase().contains("thread")) return true;
        return false;
    }

    private static synchronized void ensureTickHookRegistered() {
        if (tickHookRegistered) return;
        try {
            FMLCommonHandler.instance().bus().register(TickDrain.INSTANCE);
            tickHookRegistered = true;
        } catch (Throwable t) {
            System.err.println("[Klaymore MainThreadDispatcher] WARN: register tick hook failed: "
                + t.getMessage());
        }
    }

    /** 服务端和客户端 tick 都会触发的 drain hook（用最高频率的通用 TickEvent 即可） */
    public static final class TickDrain {
        static final TickDrain INSTANCE = new TickDrain();

        @SubscribeEvent
        public void onTick(TickEvent event) {
            if (event == null || event.phase != TickEvent.Phase.START) return;
            drainAll();
        }

        private static void drainAll() {
            Runnable r;
            while ((r = pendingQueue.poll()) != null) {
                try {
                    r.run();
                } catch (Throwable t) {
                    System.err.println("[Klaymore MainThreadDispatcher] EXCEPTION during drain: "
                        + t.getMessage());
                    t.printStackTrace(System.err);
                }
            }
        }
    }
}
