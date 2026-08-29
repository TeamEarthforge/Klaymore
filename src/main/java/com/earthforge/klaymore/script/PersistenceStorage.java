package com.earthforge.klaymore.script;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

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

/**
 * PersistenceStorage
 * ------------------
 * 脚本绑定持久化存储。
 *
 * 【为什么是 Java 而不是 Kotlin】
 * Minecraft 1.7.10 Forge 使用 LaunchClassLoader 加载 mods 目录下的 JAR。
 * Kotlin 的 object / inline / stdlib 类会在类加载早期阶段触发 kotlin.jvm.internal.Intrinsics、
 * kotlin.jvm.functions.Function0 等类加载；而这些类存在于 klaymore-runtime.jar （shadow 打进去的 stdlib），
 * 如果在扫描阶段因为 Multi-Release JAR versions/9 条目触发 ASM 5 IllegalArgumentException，
 * 整个 runtime jar 会被 FML ignore，随后任何 Kotlin 侧对这些 stdlib 类的符号解析都会
 * NoClassDefFoundError → BootstrapMethodError → 世界崩。
 *
 * 本类仅依赖 Forge/Minecraft 的公开 API + Gson（Forge 自带 2.2.4 / 项目已 shadow），
 * 不引用任何 Kotlin 类，确保即使 Kotlin runtime jar 早期出问题也不阻断服务器启动。
 */
public final class PersistenceStorage {

    private static final String BINDINGS_FILE = "bindings.json";
    private static final String SCRIPT_DIR = "klaymore";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type BINDING_MAP_TYPE = new TypeToken<Map<String, BindingEntry>>() {}.getType();

    /** 从磁盘加载的完整缓存（含尚未 resolve 的离线玩家条目，关闭时完整写回） */
    private static Map<String, BindingEntry> cachedBindings = Collections.emptyMap();
    /** 已经成功绑定到游戏对象的 key 集合（懒加载重试时跳过） */
    private static final Set<String> boundKeys = new HashSet<String>();
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

    public static File getScriptDirectory() {
        File worldDir = getWorldDirectorySafe();
        if (worldDir == null) return null;
        File klaymoreDir = new File(worldDir, SCRIPT_DIR);
        if (!klaymoreDir.exists()) {
            if (!klaymoreDir.mkdirs()) {
                System.err.println("[Klaymore PersistenceStorage] WARN: cannot mkdir: " + klaymoreDir.getAbsolutePath());
            }
        }
        return klaymoreDir;
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

        File bindingsFile = getBindingsFileSafe();
        if (bindingsFile == null) return;

        boundKeys.clear();

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

        File scriptDir = getScriptDirectory();

        // 迭代快照，防并发
        List<Map.Entry<String, BindingEntry>> snapshot =
            new ArrayList<Map.Entry<String, BindingEntry>>(cachedBindings.entrySet());
        for (Map.Entry<String, BindingEntry> e : snapshot) {
            tryBindEntry(e.getKey(), e.getValue(), scriptDir);
        }
    }

    // ---------- 内部：懒加载重试（玩家登录 / 实体加入世界） ----------

    private static void onPlayerLogin(EntityPlayer player) {
        if (player == null) return;
        UUID playerUuid = player.getUniqueID();
        String playerKey = "entity:" + playerUuid;
        System.out.println("[Klaymore PersistenceStorage] PlayerLoggedIn check lazy bind: " + playerKey);

        File scriptDir = getScriptDirectory();
        List<Map.Entry<String, BindingEntry>> snapshot =
            new ArrayList<Map.Entry<String, BindingEntry>>(cachedBindings.entrySet());
        for (Map.Entry<String, BindingEntry> e : snapshot) {
            String key = e.getKey();
            if (boundKeys.contains(key)) continue;
            if (matchesEntityUuid(key, playerUuid)) {
                tryBindEntry(key, e.getValue(), scriptDir);
            }
        }
    }

    private static void onEntityJoinWorld(Entity entity) {
        if (entity == null) return;
        if (entity.worldObj == null || entity.worldObj.isRemote) return;
        UUID entityUuid = entity.getUniqueID();
        if (entityUuid == null) return;

        File scriptDir = getScriptDirectory();
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
                tryBindEntry(key, e.getValue(), scriptDir);
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

    private static boolean tryBindEntry(String key, BindingEntry entry, File scriptDir) {
        if (key == null || entry == null) return false;
        if (boundKeys.contains(key)) return true;

        Object target = null;
        try { target = PersistenceManager.resolve(key); }
        catch (Throwable t) {
            System.err.println("[Klaymore PersistenceStorage] resolve exception key=[" + key + "]: " + t.getMessage());
            return false;
        }
        if (target == null) {
            System.out.println("[Klaymore PersistenceStorage] Key [" + key
                + "] not resolvable yet (player offline?), kept for lazy retry");
            return false;
        }

        if (scriptDir == null) {
            System.err.println("[Klaymore PersistenceStorage] skip [" + key + "]: script dir unknown");
            return false;
        }

        File scriptFile = new File(scriptDir, entry.script);
        if (!scriptFile.exists() || !scriptFile.isFile()) {
            System.err.println("[Klaymore PersistenceStorage] skip [" + key
                + "]: script file missing -> " + scriptFile.getAbsolutePath());
            return false;
        }

        ScriptContainer container = null;
        try {
            container = ScriptContainerFactory.createAndMount(
                entry.script, scriptFile, target, null);
        } catch (Throwable t) {
            System.err.println("[Klaymore PersistenceStorage] createAndMount failed ["
                + key + "] -> " + entry.script + ": " + t.getMessage());
        }
        if (container == null) {
            return false;
        }

        try {
            container.importPersistentData(entry.data);
        } catch (Throwable t) {
            System.err.println("[Klaymore PersistenceStorage] importPersistentData failed ["
                + key + "]: " + t.getMessage());
        }

        boundKeys.add(key);
        System.out.println("[Klaymore PersistenceStorage] Restored binding ["
            + key + "] -> " + entry.script);
        return true;
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
        File klaymoreDir = new File(worldDir, SCRIPT_DIR);
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
                onPlayerLogin(event.player);
            } catch (Throwable t) {
                System.err.println("[Klaymore PersistenceStorage] ERROR in onPlayerLoggedIn handler: "
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
