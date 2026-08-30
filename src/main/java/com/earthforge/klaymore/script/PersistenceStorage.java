package com.earthforge.klaymore.script;

import com.earthforge.klaymore.MinecraftDirectory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

import kotlin.script.experimental.api.CompiledScript;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PersistenceStorage
 * ------------------
 * 脚本绑定持久化存储。
 *
 * ┌──────────────────────────────────────────────────────────────────┐
 * │                        目录策略（2026-08-30 调整）              │
 * ├──────────────────────┬───────────────────────────────────────────┤
 * │  脚本文件 (.kts)      │ 全局共享，所有世界复用                    │
 * │                      │ → .minecraft/klaymore/  (client)          │
 * │                      │ → <server_root>/klaymore/ (dedicated)     │
 * │                      │    （MinecraftDirectory.getGlobalScriptDirectory())│
 * ├──────────────────────┼───────────────────────────────────────────┤
 * │  bindings.json       │ 每个存档独立（绑定关系是世界私有数据）    │
 * │                      │ → <SaveDir>/klaymore/bindings.json        │
 * │                      │    （例如 saves/New World/klaymore/）     │
 * ├──────────────────────┼───────────────────────────────────────────┤
 * │  向后兼容：脚本查找  │ 若全局目录未找到该脚本，回退尝试旧位置：   │
 * │                      │ → <SaveDir>/klaymore/<脚本名>.kts         │
 * └──────────────────────┴───────────────────────────────────────────┘
 *
 * 【为什么脚本放全局】
 *   - 编译时机前置：Mod 初始化阶段（PostInitializationEvent）就能扫描
 *     并丢给后台线程异步预编译所有 .kts，玩家点击「进入世界」之前
 *     就已经全部编译完成 → 进地图时 compileCache 全命中，挂载瞬间完成。
 *   - 避免每个存档复制同一份脚本。
 *   - 专用服管理员改一次脚本，所有世界同时生效。
 *
 * 【为什么 bindings.json 仍在存档内】
 *   - 绑定是「世界 -> 实体 -> 脚本」的映射，不同世界 NPC UUID 不同。
 *   - A 世界的某个 NPC 绑定了 Boss.kts，不应该自动跑到 B 世界。
 *
 * 【为什么是 Java 而不是 Kotlin】
 *   Minecraft 1.7.10 Forge 使用 LaunchClassLoader 加载 mods 目录下的 JAR。
 *   Kotlin 的 object / inline / stdlib 类会在类加载早期阶段触发
 *   kotlin.jvm.internal.Intrinsics 等类加载；而这些类存在于 klaymore-runtime.jar
 *   （shadow 打进去的 stdlib），如果扫描阶段触发 ASM 5 Multi-Release JAR bug，
 *   整个 runtime jar 被 FML ignore，随后任何 Kotlin 侧调用都会 NoClassDefFoundError。
 *
 *   本类仅依赖 Forge/Minecraft 公开 API + Gson，不引用任何 Kotlin 侧实现
 *   （除了 CompiledScript 类型名，loadScriptAsync 必须传入的 Consumer 泛型边界而已）
 *   确保即便 Kotlin runtime 出问题，也不阻断启动流程。
 */
public final class PersistenceStorage {

    private static final String BINDINGS_FILE = "bindings.json";
    /** 存档子目录名（bindings.json 放在 <SaveDir>/SAVE_SUBDIR_NAME/ 下） */
    private static final String SAVE_SUBDIR_NAME = "klaymore";
    /** 已经提交预编译的脚本文件名集合（避免 PostInit + serverStarting 重复编译） */
    private static final Set<String> PRECOMPILE_SUBMITTED = Collections.synchronizedSet(new HashSet<String>());

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type BINDING_MAP_TYPE = new TypeToken<Map<String, BindingEntry>>() {}.getType();

    /** 从磁盘加载的完整缓存（含尚未 resolve 的离线玩家条目，关闭时完整写回） */
    private static Map<String, BindingEntry> cachedBindings = Collections.emptyMap();
    /** 已经成功绑定到游戏对象的 key 集合（懒加载重试时跳过） */
    private static final Set<String> boundKeys = new HashSet<String>();
    /** 已提交异步编译但还未完成的 key 集合（防止同一个 key 被重复提交任务） */
    private static final Set<String> pendingKeys = new HashSet<String>();
    /** FML 事件总线注册标记（只注册一次） */
    private static boolean eventBusRegistered = false;

    private PersistenceStorage() {}

    // ---------- Public API ----------

    public static synchronized void initialize() {
        if (!eventBusRegistered) {
            try {
                FMLCommonHandler.instance().bus().register(EventBusListener.INSTANCE);
                MinecraftForge.EVENT_BUS.register(EventBusListener.INSTANCE);
                eventBusRegistered = true;
            } catch (Throwable t) {
                System.err.println("[Klaymore PersistenceStorage] WARN: register event bus failed: " + t.getMessage());
            }
        }
    }

    /**
     * 脚本目录 = 全局共享目录。
     * 所有世界都复用同一套脚本，允许编译前置到 Mod 初始化阶段。
     * （位置：.minecraft/klaymore 或 <server_root>/klaymore）
     */
    public static File getScriptDirectory() {
        return MinecraftDirectory.getGlobalScriptDirectory();
    }

    /**
     * 旧的存档内 klaymore 目录（仅用于向后兼容：resolveScriptFile 的 fallback）。
     * 之前脚本和 bindings.json 一起放在 <SaveDir>/klaymore/ 下，有些用户有旧脚本，
     * 这里保留以便平滑迁移。
     */
    private static File getLegacySaveScriptDirectory() {
        File worldDir = getWorldDirectorySafe();
        if (worldDir == null) return null;
        File klaymoreDir = new File(worldDir, SAVE_SUBDIR_NAME);
        if (!klaymoreDir.exists()) {
            if (!klaymoreDir.mkdirs()) {
                // 只返回 null 时是错误，但这里 fallback 不需要抛；如果是 migrate 可以后续 warn
            }
        }
        return klaymoreDir;
    }

    /**
     * 按「脚本文件名」解析真实文件路径。
     *   优先级 1：全局脚本目录 → .minecraft/klaymore/<name>
     *   优先级 2：fallback 存档旧目录 → <SaveDir>/klaymore/<name>
     *   都找不到 → 返回一个在全局目录下的 File（让上层报 missing，同时提示应该放哪里）
     */
    public static File resolveScriptFile(String scriptName) {
        if (scriptName == null) return null;
        // 1. 全局
        File globalDir = getScriptDirectory();
        if (globalDir != null) {
            File f = new File(globalDir, scriptName);
            if (f.exists() && f.isFile()) return f;
        }
        // 2. 存档内旧位置（兼容）
        File legacyDir = getLegacySaveScriptDirectory();
        if (legacyDir != null) {
            File f2 = new File(legacyDir, scriptName);
            if (f2.exists() && f2.isFile()) {
                System.out.println("[Klaymore PersistenceStorage] resolved script from legacy save location: "
                    + f2.getAbsolutePath() + " (建议迁移到全局脚本目录)");
                return f2;
            }
        }
        // 3. 都没找到 → 返回全局目录下的 File（让统一的 "file missing" 提示带正确路径）
        return new File(globalDir, scriptName);
    }

    // ---------- 预编译：把所有脚本提前编译好，进世界时 0 等待 ----------

    /**
     * 立即提交所有发现的脚本文件到后台预编译。
     * 可以多次安全调用（内部按文件名去重）。
     *
     * 建议调用时机：
     *   ① FMLPostInitializationEvent  → Mod 初始化刚结束，开始后台预热（玩家可能还在主菜单）
     *   ② FMLServerStartingEvent  → 进入世界前的最后一个时机，哪怕 PostInit 没跑也能补上
     *   ③ /klaymore reload 时 invalidateCache 后 → 同上
     */
    public static void precompileAllScriptsNow() {
        File scriptDir = getScriptDirectory();
        if (scriptDir == null || !scriptDir.isDirectory()) return;
        File[] files = scriptDir.listFiles();
        if (files == null || files.length == 0) return;

        final List<File> toCompile = new ArrayList<File>();
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            if (!f.getName().toLowerCase().endsWith(".kts")) continue;
            String canonicalKey;
            try { canonicalKey = f.getCanonicalPath(); }
            catch (Throwable t) { canonicalKey = f.getAbsolutePath(); }
            if (!PRECOMPILE_SUBMITTED.add(canonicalKey)) continue; // 去重
            toCompile.add(f);
        }
        if (toCompile.isEmpty()) return;

        final AtomicInteger remain = new AtomicInteger(toCompile.size());
        System.out.println("[Klaymore PersistenceStorage] Submit background pre-compile: "
            + toCompile.size() + " scripts in " + scriptDir.getAbsolutePath());

        for (final File f : toCompile) {
            try {
                ScriptLoader.loadScriptAsync(f, new java.util.function.Consumer<CompiledScript>() {
                    @Override
                    public void accept(CompiledScript compiled) {
                        int left = remain.decrementAndGet();
                        if (compiled != null) {
                            System.out.println("[Klaymore PersistenceStorage] pre-compile OK: "
                                + f.getName() + " (remaining=" + left + ")");
                        } else {
                            String lastErr = ScriptErrorReporter.getLastError();
                            System.err.println("[Klaymore PersistenceStorage] pre-compile FAILED: "
                                + f.getName()
                                + (lastErr != null ? " -> " + lastErr : "")
                                + " (remaining=" + left + ")");
                        }
                        if (left == 0) {
                            System.out.println("[Klaymore PersistenceStorage] All script pre-compile tasks dispatched.");
                        }
                    }
                });
            } catch (Throwable t) {
                remain.decrementAndGet();
                System.err.println("[Klaymore PersistenceStorage] WARN: submit pre-compile for "
                    + f.getName() + " failed: " + t.getMessage());
            }
        }

        // 额外：把存档旧目录里的脚本也预编译一遍（方便正在迁移的用户）
        File legacy = getLegacySaveScriptDirectory();
        if (legacy != null && legacy.isDirectory() && !legacy.equals(scriptDir)) {
            File[] legacyFiles = legacy.listFiles();
            if (legacyFiles != null) {
                for (final File f : legacyFiles) {
                    if (!f.isFile() || !f.getName().toLowerCase().endsWith(".kts")) continue;
                    String canonicalKey;
                    try { canonicalKey = f.getCanonicalPath(); }
                    catch (Throwable t) { canonicalKey = f.getAbsolutePath(); }
                    if (!PRECOMPILE_SUBMITTED.add(canonicalKey)) continue;
                    try {
                        ScriptLoader.loadScriptAsync(f, new java.util.function.Consumer<CompiledScript>() {
                            @Override public void accept(CompiledScript c) { /* 只编译不报错提示 */ }
                        });
                    } catch (Throwable ignore) {}
                }
            }
        }
    }

    /** 丢弃预编译去重集合（执行 /reload 时先清 cache 再调它，就会重新编译所有脚本） */
    public static void resetPrecompileMarkers() {
        PRECOMPILE_SUBMITTED.clear();
    }

    public static synchronized void saveAll() {
        File bindingsFile = getBindingsFileSafe();
        if (bindingsFile == null) return;

        Map<String, BindingEntry> entries = new LinkedHashMap<String, BindingEntry>();

        List<ScriptContainer> containers = safeGetContainers();
        for (ScriptContainer container : containers) {
            Object target = null;
            try { target = container.getTarget(); } catch (Throwable ignored) {}
            if (target == null) continue;

            String key = null;
            try { key = PersistenceManager.generateKey(target); } catch (Throwable ignored) {}
            if (key == null) continue;

            Map<String, ?> data;
            try { data = container.exportPersistentData(); }
            catch (Throwable t) { data = Collections.emptyMap(); }

            entries.put(key, new BindingEntry(container.getScriptName(), data));
        }

        // 把尚未 resolve（比如离线玩家）的原始条目重新合并，不丢数据
        for (Map.Entry<String, BindingEntry> old : cachedBindings.entrySet()) {
            if (!entries.containsKey(old.getKey())) {
                entries.put(old.getKey(), old.getValue());
            }
        }

        FileWriter writer = null;
        try {
            writer = new FileWriter(bindingsFile);
            GSON.toJson(entries, BINDING_MAP_TYPE, writer);
            System.out.println("[Klaymore PersistenceStorage] Saved " + entries.size()
                + " bindings -> " + bindingsFile.getAbsolutePath());
        } catch (Throwable t) {
            System.err.println("[Klaymore PersistenceStorage] ERROR saveAll: " + t.getMessage());
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public static synchronized void loadAll() {
        initialize();

        // ⭐ 预编译所有脚本（进入世界前的最后机会）
        // 正常情况下 PostInit 就已经提交过了，但：
        //   - 专用服务器上 PostInit 时 world save handler 还没初始化好
        //   - 用户是第一次进入世界，PostInit 时目录不存在，之后才被创建
        // 所以这里再触发一次；去重集合会自动跳过已经提交过的脚本。
        precompileAllScriptsNow();

        File bindingsFile = getBindingsFileSafe();
        if (bindingsFile == null) return;

        boundKeys.clear();
        pendingKeys.clear();

        if (!bindingsFile.exists()) {
            cachedBindings = Collections.emptyMap();
            return;
        }

        FileReader reader = null;
        try {
            reader = new FileReader(bindingsFile);
            Map<String, BindingEntry> loaded = GSON.fromJson(reader, BINDING_MAP_TYPE);
            cachedBindings = (loaded != null) ? loaded : Collections.<String, BindingEntry>emptyMap();
        } catch (Throwable t) {
            System.err.println("[Klaymore PersistenceStorage] ERROR loadAll parse: " + t.getMessage());
            cachedBindings = Collections.emptyMap();
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Throwable ignored) {}
            }
        }

        System.out.println("[Klaymore PersistenceStorage] Read " + cachedBindings.size()
            + " entries from disk: " + bindingsFile.getAbsolutePath());

        // ⭐ 优化：bindings.json 中引用到的脚本，即便没有在全局目录预编译扫描里，
        // 也提前扔进异步编译池（比如引用了子目录里的脚本，或刚迁移的存档内旧脚本）。
        // 等实体真正加载完触发 EntityJoinWorld 时，编译早完成了 → 零等待挂载。
        for (BindingEntry entry : cachedBindings.values()) {
            File f = resolveScriptFile(entry.script);
            if (f != null && f.exists() && f.isFile()) {
                String canonicalKey;
                try { canonicalKey = f.getCanonicalPath(); }
                catch (Throwable t) { canonicalKey = f.getAbsolutePath(); }
                if (!PRECOMPILE_SUBMITTED.add(canonicalKey)) continue;
                try {
                    // 预编译：回调空，只写入 compileCache 就够了
                    ScriptLoader.loadScriptAsync(f, new java.util.function.Consumer<CompiledScript>() {
                        @Override public void accept(CompiledScript c) { }
                    });
                } catch (Throwable ignore) {}
            }
        }

        // 启动时扫描：实体尚未加载 → resolve 几乎都 null（除了已经在 server.worldServers 里的极少实例）
        // 真正的绑定由 EntityJoinWorld / PlayerLoggedIn 事件触发；
        // 这里保留扫描仅为了打印日志数量统计 + 兼容极少数"服务器 tick 中加入的实体"情况。
        List<Map.Entry<String, BindingEntry>> snapshot =
            new ArrayList<Map.Entry<String, BindingEntry>>(cachedBindings.entrySet());
        for (Map.Entry<String, BindingEntry> e : snapshot) {
            tryBindEntry(e.getKey(), e.getValue(), null);
        }
    }

    // ---------- 内部：懒加载重试（玩家登录 / 实体加入世界） ----------

    private static void handlePlayerLogin(EntityPlayer player) {
        if (player == null) return;
        UUID playerUuid = player.getUniqueID();
        String playerKey = "entity:" + playerUuid;
        System.out.println("[Klaymore PersistenceStorage] PlayerLoggedIn check lazy bind: " + playerKey);

        List<Map.Entry<String, BindingEntry>> snapshot =
            new ArrayList<Map.Entry<String, BindingEntry>>(cachedBindings.entrySet());
        for (Map.Entry<String, BindingEntry> e : snapshot) {
            String key = e.getKey();
            if (boundKeys.contains(key)) continue;
            if (matchesEntityUuid(key, playerUuid)) {
                // ⭐ 玩家对象已经在 event 里拿到了，不需要再走 resolve() 反查
                tryBindEntry(key, e.getValue(), null, player);
            }
        }
    }

    private static void handleEntityJoin(Entity entity) {
        if (entity == null) return;
        if (entity.worldObj == null || entity.worldObj.isRemote) return;
        UUID entityUuid = entity.getUniqueID();
        if (entityUuid == null) return;

        List<Map.Entry<String, BindingEntry>> snapshot =
            new ArrayList<Map.Entry<String, BindingEntry>>(cachedBindings.entrySet());
        int attemptCount = 0;
        for (Map.Entry<String, BindingEntry> e : snapshot) {
            String key = e.getKey();
            if (boundKeys.contains(key)) continue;
            if (matchesEntityUuid(key, entityUuid)) {
                if (attemptCount == 0) {
                    System.out.println("[Klaymore PersistenceStorage] EntityJoinWorld lazy bind for entity:"
                        + entityUuid + " (class=" + entity.getClass().getSimpleName() + ")");
                }
                attemptCount++;
                // ⭐ 关键优化：事件直接给出了 entity 对象，直接用它做 target！
                // 之前依赖 PersistenceManager.resolve() → EntityHelper.getEntityByUUID()，
                // 老版本 EntityHelper 用了 1.14+ 的类名，永远返回 null → 绑定永远不发生。
                tryBindEntry(key, e.getValue(), null, entity);
            }
        }
    }

    private static boolean matchesEntityUuid(String key, UUID entityUuid) {
        if (key == null || !key.startsWith("entity:")) return false;
        String tail = key.substring("entity:".length());
        try {
            return UUID.fromString(tail).equals(entityUuid);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    // ---------- 内部：绑定尝试 ----------

    /** 启动时扫描（没有现成实体对象）：必须走 resolve() */
    private static void tryBindEntry(String key, BindingEntry entry, File ignoredLegacyScriptDir) {
        tryBindEntry(key, entry, ignoredLegacyScriptDir, null);
    }

    /**
     * 通用绑定入口（事件回调 / 启动扫描都走这个）。
     *
     * @param preResolvedTarget 如果 != null 就直接用它（不再走 resolve），
     *                          用于 handleEntityJoin / handlePlayerLogin，
     *                          因为事件参数里已经给了我们活生生的 Entity 对象。
     * @param ignoredLegacyScriptDir 旧的「存档内 script 目录」参数，现已废弃，
     *                               脚本文件统一走 resolveScriptFile()（全局优先 + 存档 fallback）。
     */
    private static void tryBindEntry(
        final String key,
        final BindingEntry entry,
        final File ignoredLegacyScriptDir,
        Object preResolvedTarget
    ) {
        if (key == null || entry == null) return;
        if (boundKeys.contains(key)) return;
        if (pendingKeys.contains(key)) return;

        Object target = preResolvedTarget;
        if (target == null) {
            try { target = PersistenceManager.resolve(key); }
            catch (Throwable t) {
                System.err.println("[Klaymore PersistenceStorage] resolve exception key=[" + key + "]: " + t.getMessage());
                return;
            }
            if (target == null) {
                // serverStarting 阶段，实体还没加载，99% 情况会落到这里 → 不要打印太多日志，
                // 等 EntityJoinWorld 事件时再真正尝试绑定并打印
                return;
            }
        } else {
            // 防御性验证：检查给定的 target 是否和 key 中的 UUID 匹配（针对 entity: 前缀）
            if (key.startsWith("entity:") && (target instanceof Entity)) {
                try {
                    UUID actual = ((Entity) target).getUniqueID();
                    String expectedTail = key.substring("entity:".length());
                    if (actual != null && !actual.toString().equals(expectedTail)) {
                        System.err.println("[Klaymore PersistenceStorage] WARN: key [" + key
                            + "] uuid mismatch with provided target. Skipping bind.");
                        return;
                    }
                } catch (Throwable ignore) {}
            }
        }

        // ⭐ 新策略：resolveScriptFile() — 全局优先，存档 fallback
        final File scriptFile = resolveScriptFile(entry.script);
        if (scriptFile == null || !scriptFile.exists() || !scriptFile.isFile()) {
            System.err.println("[Klaymore PersistenceStorage] skip [" + key
                + "]: script file missing -> "
                + (scriptFile == null ? "(null)" : scriptFile.getAbsolutePath())
                + " (请把脚本放到全局脚本目录: " + getScriptDirectory() + ")");
            return;
        }

        final Object fTarget = target;
        final Map<String, ?> fData = entry.data;
        final String fScriptName = entry.script;

        // 提交异步编译前先标记 pending，防止同一个 key 重复入队
        pendingKeys.add(key);

        System.out.println("[Klaymore PersistenceStorage] Submit async bind [" + key
            + "] -> " + fScriptName);

        try {
            ScriptContainerFactory.createAndMountAsync(
                fScriptName, scriptFile, fTarget, null,
                new java.util.function.Consumer<ScriptContainer>() {
                    @Override
                    public void accept(ScriptContainer container) {
                        // 回调里一定是主线程
                        pendingKeys.remove(key);

                        if (container == null) {
                            System.err.println("[Klaymore PersistenceStorage] async bind FAILED ["
                                + key + "] -> " + fScriptName);
                            return;
                        }

                        try {
                            container.importPersistentData(fData);
                        } catch (Throwable t) {
                            System.err.println("[Klaymore PersistenceStorage] importPersistentData failed ["
                                + key + "]: " + t.getMessage());
                        }

                        boundKeys.add(key);
                        System.out.println("[Klaymore PersistenceStorage] Restored binding ["
                            + key + "] -> " + fScriptName);
                    }
                });
        } catch (Throwable t) {
            pendingKeys.remove(key);
            System.err.println("[Klaymore PersistenceStorage] submit async bind failed ["
                + key + "] -> " + fScriptName + ": " + t.getMessage());
        }
    }

    // ---------- 内部：工具方法 ----------

    private static List<ScriptContainer> safeGetContainers() {
        try {
            List<ScriptContainer> list = ScriptBindingManager.getContainers();
            return list != null ? list : Collections.<ScriptContainer>emptyList();
        } catch (Throwable t) {
            System.err.println("[Klaymore PersistenceStorage] WARN ScriptBindingManager unavailable: "
                + t.getMessage());
            return Collections.emptyList();
        }
    }

    private static File getBindingsFileSafe() {
        File worldDir = getWorldDirectorySafe();
        if (worldDir == null) return null;
        // bindings.json 永远是世界私有数据，不随全局脚本变
        File klaymoreDir = new File(worldDir, SAVE_SUBDIR_NAME);
        if (!klaymoreDir.exists()) {
            if (!klaymoreDir.mkdirs()) return null;
        }
        return new File(klaymoreDir, BINDINGS_FILE);
    }

    private static File getWorldDirectorySafe() {
        try {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null) return null;

            // 优先 entityWorld（1.7.10 服务端常用）
            World world = server.getEntityWorld();
            if (world != null) {
                ISaveHandler sh = world.getSaveHandler();
                if (sh != null) {
                    File dir = sh.getWorldDirectory();
                    if (dir != null) return dir;
                }
            }

            // 回退：遍历 worldServers[0]
            World[] worlds = server.worldServers;
            if (worlds != null && worlds.length > 0 && worlds[0] != null) {
                ISaveHandler sh = worlds[0].getSaveHandler();
                if (sh != null) {
                    File dir = sh.getWorldDirectory();
                    if (dir != null) return dir;
                }
            }
        } catch (Throwable t) {
            System.err.println("[Klaymore PersistenceStorage] WARN getWorldDirectory: " + t.getMessage());
        }
        return null;
    }

    // ---------- 内部：事件监听器 ----------
    // ⚠ 必须是 public static 类！Forge 的 ASMEventHandler 会运行时动态生成字节码类来直接调用
    // 事件回调，如果类或方法不是 public（哪怕是 outer class 能访问的 private inner），
    // JVM 都会抛 IllegalAccessError（ASM 字节码类不是 PersistenceStorage 的宿主，无权访问 private 内部）。

    public static final class EventBusListener {
        static final EventBusListener INSTANCE = new EventBusListener();

        @SubscribeEvent
        public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
            if (event == null) return;
            try {
                handlePlayerLogin(event.player);
            } catch (Throwable t) {
                System.err.println("[Klaymore PersistenceStorage] ERROR in onPlayerLoggedIn handler: "
                    + t.getMessage());
            }
        }

        @SubscribeEvent
        public void onEntityJoinWorld(EntityJoinWorldEvent event) {
            if (event == null) return;
            try {
                handleEntityJoin(event.entity);
            } catch (Throwable t) {
                System.err.println("[Klaymore PersistenceStorage] ERROR in onEntityJoinWorld handler: "
                    + t.getMessage());
            }
        }
    }

    // ---------- 内部：BindingEntry POJO（Gson 序列化用） ----------

    public static final class BindingEntry {
        public String script;
        public Map<String, ?> data;

        public BindingEntry() {
            this.script = "";
            this.data = new HashMap<String, Object>();
        }

        public BindingEntry(String script, Map<String, ?> data) {
            this.script = script;
            this.data = (data != null) ? data : Collections.<String, Object>emptyMap();
        }
    }
}
