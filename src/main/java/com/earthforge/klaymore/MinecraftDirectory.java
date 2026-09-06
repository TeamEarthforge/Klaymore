package com.earthforge.klaymore;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.storage.ISaveHandler;

import cpw.mods.fml.common.FMLCommonHandler;

/**
 * MinecraftDirectory
 * -------------------
 * 安全地定位 .minecraft 根目录（客户端）或服务器根目录（专用服务器）。
 *
 * 为什么需要这个：
 * - 客户端：脚本放在 .minecraft/klaymore/（与 saves/ 同级，全局共享，所有世界复用）
 * - 专用服务器：脚本放在 <server_root>/klaymore/（与 world/ 同级）
 * - 这样脚本不需要每个世界复制一份，并且**编译时机可以前置到 Mod 初始化阶段**，
 * 玩家进入地图时脚本已经编译好了（compileCache 命中），挂载 ≤1ms，零卡顿。
 *
 * 定位策略优先级：
 * 1. 客户端：反射 Minecraft.mcDataDir（1.7.10 字段就是这个名，public File）
 * 2. 集成 / 专用服务端：从 world save 目录向上反推
 * - 若是 saves/<WorldName>/ 结构 → saves 的父目录就是 .minecraft
 * - 若是 world/ 目录结构（专用服） → 父目录就是 server 根
 * 3. Fallback：new File(".")（当前工作目录）
 */
public final class MinecraftDirectory {

    private static volatile File cachedRoot = null;
    private static final String GLOBAL_SCRIPT_DIRNAME = "klaymore";

    private MinecraftDirectory() {}

    /** 拿到 .minecraft 或服务器根目录 */
    public static File getRoot() {
        if (cachedRoot != null) return cachedRoot;
        synchronized (MinecraftDirectory.class) {
            if (cachedRoot != null) return cachedRoot;
            File resolved = resolveInternal();
            if (resolved == null) resolved = new File(".");
            cachedRoot = resolved;
            return cachedRoot;
        }
    }

    /**
     * 获取全局脚本目录 = <mcRoot>/klaymore
     * 脚本文件是全局共享的，每个存档只需保存 bindings.json 这种轻量绑定关系。
     */
    public static File getGlobalScriptDirectory() {
        File dir = new File(getRoot(), GLOBAL_SCRIPT_DIRNAME);
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                System.err.println(
                    "[Klaymore MinecraftDirectory] WARN: cannot mkdir global scripts dir: " + dir.getAbsolutePath());
            }
        }
        return dir;
    }

    /**
     * 获取全局缓存目录 = <mcRoot>/.klaymore-cache
     * 编译产物（.class 字节码）等可丢弃的中间数据放在这里，与脚本目录分开，
     * 避免污染源码目录。脚本被全局共享，缓存也按全局存放。
     */
    public static File getGlobalCacheDirectory() {
        File dir = new File(getRoot(), ".klaymore-cache");
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                System.err.println(
                    "[Klaymore MinecraftDirectory] WARN: cannot mkdir global cache dir: " + dir.getAbsolutePath());
            }
        }
        return dir;
    }

    // ---------- internal ----------

    private static File resolveInternal() {
        // ---- 1. 客户端优先：拿 Minecraft.mcDataDir ----
        if (FMLCommonHandler.instance()
            .getSide()
            .isClient()) {
            try {
                Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft");
                Method getMc = mcCls.getMethod("getMinecraft");
                Object mc = getMc.invoke(null);
                if (mc != null) {
                    Field fDataDir = mcCls.getDeclaredField("mcDataDir");
                    fDataDir.setAccessible(true);
                    File dir = (File) fDataDir.get(mc);
                    if (dir != null && dir.isDirectory()) return dir;
                    // fallback 字段名 dataDir / field_71412_E (SRG)
                    try {
                        Field f2 = mcCls.getDeclaredField("dataDir");
                        f2.setAccessible(true);
                        File d2 = (File) f2.get(mc);
                        if (d2 != null && d2.isDirectory()) return d2;
                    } catch (Throwable ignore) {}
                }
            } catch (Throwable ignore) {
                // 服务器侧不会有客户端类，正常吞掉
            }
        }

        // ---- 2. 集成服 / 专用服：尝试从 world save 反推 ----
        try {
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null) {
                // 专用服务器通常有 getFile("") 返回服务器根，或 dataDir 字段
                try {
                    Method getFile = MinecraftServer.class.getMethod("getFile", String.class);
                    File f = (File) getFile.invoke(server, "");
                    if (f != null && f.isDirectory()) return f;
                } catch (Throwable ignore) {}

                // 从 worldSaveHandler 反推 saves 父目录
                World w = server.getEntityWorld();
                if (w != null) {
                    ISaveHandler sh = w.getSaveHandler();
                    if (sh != null) {
                        File worldDir = sh.getWorldDirectory();
                        if (worldDir != null) {
                            File parent = worldDir.getParentFile();
                            if (parent != null) {
                                // saves/<World> → parent 名是 "saves"，再上一层就是 .minecraft
                                if ("saves".equalsIgnoreCase(parent.getName())) {
                                    File grand = parent.getParentFile();
                                    if (grand != null && grand.isDirectory()) return grand;
                                }
                                // 专用服务器 world/ → parent 就是 server root
                                if (parent.isDirectory()) return parent;
                            }
                            return worldDir;
                        }
                    }
                }

                // 最后尝试 server.worldServers[0]
                World[] worlds = server.worldServers;
                if (worlds != null && worlds.length > 0 && worlds[0] != null) {
                    ISaveHandler sh = worlds[0].getSaveHandler();
                    if (sh != null) {
                        File d = sh.getWorldDirectory();
                        if (d != null && d.getParentFile() != null) return d.getParentFile()
                            .getParentFile() != null && "saves".equalsIgnoreCase(
                                d.getParentFile()
                                    .getName()) ? d.getParentFile()
                                        .getParentFile() : d.getParentFile();
                    }
                }
            }
        } catch (Throwable ignore) {}

        // ---- 3. Fallback ----
        return new File(".");
    }

    /**
     * 每次进入世界时重新检测一次。
     * （单实例客户端从 Mod 初始化 → 进入主菜单 → 进入世界，期间目录可能被重新定位过；
     * 大部分时候缓存是对的，这里保险起见提供一个手动刷新入口）
     */
    public static void invalidateCache() {
        cachedRoot = null;
    }
}
