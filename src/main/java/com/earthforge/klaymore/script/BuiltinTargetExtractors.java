package com.earthforge.klaymore.script;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.earthforge.klaymore.Klaymore;

import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 集中注册所有事件目标提取器。
 *
 * 请在这里增删你需要的提取器。
 *
 * 提取器规则说明：
 * - 输入：一个 Forge 事件对象
 * - 输出：该事件绑定的"主要游戏对象"（Entity、Player、BlockPos 等）；如果返回 null，
 * 则该次事件不会派发给任何脚本（即使脚本订阅了此事件）。
 * - 注意：一旦某事件类注册了提取器，无论提取器返回 null 与否，该事件都不会走"全广播"模式。
 * 如果某事件需要全广播，请不要注册提取器，改用 markAsNoTargetEvent 标记可消除 WARN 日志。
 */
public final class BuiltinTargetExtractors {

    private BuiltinTargetExtractors() {}

    public static void registerAll() {

        // ================================================================
        // 1. 实体相关事件：目标 = 事件涉及的实体
        // 适用：LivingHurtEvent, EntityJoinWorldEvent, LivingDeathEvent 等
        // ================================================================
        EventTargetExtractorRegistry.registerExtractor(EntityEvent.class, event -> event.entity);

        // Forge 玩家事件（net.minecraftforge.event.entity.player.PlayerEvent）
        // 虽然继承自 EntityEvent，但单独注册可兼容字段名差异（entityPlayer / player）
        EventTargetExtractorRegistry.registerExtractor(
            net.minecraftforge.event.entity.player.PlayerEvent.class,
            event -> firstNonNull(reflectGet(event, "entityPlayer"), reflectGet(event, "player")));
        Klaymore.LOG.info("[Klaymore] Extractor registered: PlayerEvent (forge) -> entityPlayer/player");

        // 生物事件（LivingHurtEvent、LivingDeathEvent 等）
        EventTargetExtractorRegistry.registerExtractor(
            LivingEvent.class,
            event -> firstNonNull(reflectGet(event, "entityLiving"), reflectGet(event, "entity")));
        Klaymore.LOG.info("[Klaymore] Extractor registered: LivingEvent -> entityLiving/entity");

        // ================================================================
        // 2. 方块相关事件：目标 = 方块位置
        // 适用：BlockEvent.BreakEvent, BlockEvent.PlaceEvent 等
        // 优先使用 BlockPos（若存在该类）；否则回退到 Triple(x,y,z)
        // ================================================================
        EventTargetExtractorRegistry.registerExtractor(BlockEvent.class, event -> {
            Object pos = reflectGet(event, "pos");
            if (pos != null) return pos;
            Integer x = (Integer) reflectGet(event, "x");
            Integer y = (Integer) reflectGet(event, "y");
            Integer z = (Integer) reflectGet(event, "z");
            if (x != null && y != null && z != null) {
                Object blockPos = tryNewBlockPos(x, y, z);
                return blockPos != null ? blockPos : new int[] { x, y, z };
            }
            return null;
        });
        Klaymore.LOG.info("[Klaymore] Extractor registered: BlockEvent -> BlockPos / int[3]");

        // ================================================================
        // 3. 聊天事件：目标 = 发消息的玩家
        // ================================================================
        EventTargetExtractorRegistry.registerExtractor(
            ServerChatEvent.class,
            event -> firstNonNull(reflectGet(event, "player"), reflectGet(event, "entityPlayer")));
        Klaymore.LOG.info("[Klaymore] Extractor registered: ServerChatEvent -> player");

        // ================================================================
        // 4. FML 玩家事件（登录 PlayerLoggedInEvent、登出、拾取物品等）
        // 对应类：cpw.mods.fml.common.gameevent.PlayerEvent
        // ================================================================
        EventTargetExtractorRegistry
            .registerExtractor(cpw.mods.fml.common.gameevent.PlayerEvent.class, event -> event.player);
        Klaymore.LOG.info("[Klaymore] Extractor registered: FML PlayerEvent -> player");

        // ================================================================
        // 5. HarvestDropsEvent —— ⭐ 多目标示例 ⭐
        // 同时把事件派发给「挖掘者玩家」和「被挖的方块位置」
        // 这就是多目标提取器：返回一个 List/数组即可
        // （用反射判断类是否存在，不存在则跳过）
        // ================================================================
        try {
            Class<?> harvestEventCls = Class.forName("net.minecraftforge.event.world.BlockEvent$HarvestDropsEvent");
            EventTargetExtractorRegistry.registerExtractor((Class) harvestEventCls, event -> {
                java.util.List<Object> list = new java.util.ArrayList<>();
                Object harvester = firstNonNull(reflectGet(event, "harvester"), reflectGet(event, "entityPlayer"));
                if (harvester != null) list.add(harvester);

                Object pos = reflectGet(event, "pos");
                if (pos != null) {
                    list.add(pos);
                } else {
                    Integer x = (Integer) reflectGet(event, "x");
                    Integer y = (Integer) reflectGet(event, "y");
                    Integer z = (Integer) reflectGet(event, "z");
                    if (x != null && y != null && z != null) {
                        Object blockPos = tryNewBlockPos(x, y, z);
                        list.add(blockPos != null ? blockPos : new int[] { x, y, z });
                    }
                }
                return list;
            });
            Klaymore.LOG.info(
                "[Klaymore] Extractor registered: BlockEvent.HarvestDropsEvent -> [harvester, blockPos] (MULTI-TARGET)");
        } catch (ClassNotFoundException ignored) {
            Klaymore.LOG.debug("[Klaymore] HarvestDropsEvent class not found, skipping multi-target registration");
        }

        // ================================================================
        // 6. 以下事件"无特定目标"，仅标记以避免 WARN 日志
        // 这些事件会以全广播模式派发给所有订阅者（不匹配目标）
        // ================================================================
        EventTargetExtractorRegistry.markAsNoTargetEvent(WorldEvent.class);
        Klaymore.LOG.info("[Klaymore] Marked as no-target (broadcast-only): WorldEvent");

        EventTargetExtractorRegistry.markAsNoTargetEvent(TickEvent.class);
        Klaymore.LOG.info("[Klaymore] Marked as no-target (broadcast-only): TickEvent");

        EventTargetExtractorRegistry.markAsNoTargetEvent(TickEvent.ServerTickEvent.class);
        Klaymore.LOG.info("[Klaymore] Marked as no-target (broadcast-only): ServerTickEvent");

        // ================================================================
        // 7. 客户端高频事件：标记为无目标（全广播），客户端脚本可订阅
        // 之前因 Intrinsics 类加载问题被 skip，现在有 KotlinPreloader 兜底，
        // 且 side 隔离后只会派发给客户端脚本，开销可控。
        // ================================================================
        // 7.1 TickEvent.RenderTickEvent（客户端渲染帧 tick，每帧几十次，纯客户端）
        markNoTargetIfExists("cpw.mods.fml.common.gameevent.TickEvent$RenderTickEvent", "RenderTickEvent");
        // 7.2 TickEvent.ClientTickEvent（客户端逻辑 tick，20Hz，纯客户端）
        markNoTargetIfExists("cpw.mods.fml.common.gameevent.TickEvent$ClientTickEvent", "ClientTickEvent");
        // 7.3 如果你发现其他客户端事件也需要脚本接收，按上面方式加一行即可
        // markNoTargetIfExists(
        // "net.minecraftforge.client.event.RenderPlayerEvent$Pre",
        // "RenderPlayerEvent.Pre"
        // );

        Klaymore.LOG.info("[Klaymore] All target extractors registered (BuiltinTargetExtractors)");

        // ================================================================
        // 示例 A：自定义 Mod 事件的单目标提取器
        // ================================================================
        // 假设你自己的 Mod 有一个事件：
        //
        // public class MySpellCastEvent extends Event {
        // public final EntityLivingBase caster;
        // public final ItemStack wand;
        // public MySpellCastEvent(EntityLivingBase caster, ItemStack wand) { ... }
        // }
        //
        // 如果你想把事件派发给"绑定到施法者实体"的脚本：
        //
        // EventTargetExtractorRegistry.registerExtractor(
        // MySpellCastEvent.class,
        // event -> event.caster
        // );
        //
        // 如果你想把事件派发给"绑定到 wand 物品堆"的脚本：
        //
        // EventTargetExtractorRegistry.registerExtractor(
        // MySpellCastEvent.class,
        // event -> event.wand
        // );
        //
        // ================================================================
        // 示例 B：自定义 Mod 事件的多目标提取器（像 HarvestDropsEvent 一样）
        // ================================================================
        // 如果 MySpellCastEvent 你既要派发给施法者，也要派发到魔杖物品：
        //
        // EventTargetExtractorRegistry.registerExtractor(
        // MySpellCastEvent.class,
        // event -> Arrays.asList(event.caster, event.wand)
        // );
        //
        // ✅ 这样：
        // - 绑定到 caster 玩家身上的脚本，能收到事件
        // - 绑定到 wand 这个 ItemStack 的脚本，也能收到事件
        // - 即使一个脚本容器鬼使神差地同时匹配了 caster 和 wand，
        // Handler 去重机制也只会调用它 1 次，绝不会重复触发
        //
        // ================================================================
        // 示例 C：屏蔽某事件（绝不派发，也不全广播）
        // ================================================================
        // 只要该事件类注册了提取器，并且永远返回空集合/数组/null，
        // 那么该事件在脚本层相当于"不存在"，全广播也不会触发：
        //
        // EventTargetExtractorRegistry.registerExtractor(
        // MySecretEvent.class,
        // event -> Collections.emptyList() // 或 null
        // );
        // ================================================================
    }

    // ---------------- 工具方法 ----------------

    /** 通过全限定名反射查找事件类；若存在则标记为 skip（跳过派发），不存在则静默忽略 */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void markSkippedIfExists(String fullyQualifiedName, String displayName) {
        try {
            Class<?> cls = Class.forName(fullyQualifiedName);
            if (cpw.mods.fml.common.eventhandler.Event.class.isAssignableFrom(cls)) {
                EventTargetExtractorRegistry.markAsSkippedEvent((Class) cls);
                Klaymore.LOG.info("[Klaymore] Marked as skipped (no dispatch): " + displayName);
            }
        } catch (ClassNotFoundException ignored) {
            Klaymore.LOG.debug("[Klaymore] Skip class not found: " + fullyQualifiedName);
        }
    }

    /** 通过全限定名反射查找事件类；若存在则标记为 no-target（全广播），不存在则静默忽略 */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void markNoTargetIfExists(String fullyQualifiedName, String displayName) {
        try {
            Class<?> cls = Class.forName(fullyQualifiedName);
            if (cpw.mods.fml.common.eventhandler.Event.class.isAssignableFrom(cls)) {
                EventTargetExtractorRegistry.markAsNoTargetEvent((Class) cls);
                Klaymore.LOG.info("[Klaymore] Marked as no-target (broadcast): " + displayName);
            }
        } catch (ClassNotFoundException ignored) {
            Klaymore.LOG.debug("[Klaymore] No-target class not found: " + fullyQualifiedName);
        }
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    /** 尝试用反射实例化 BlockPos（若类存在），失败返回 null */
    private static Object tryNewBlockPos(int x, int y, int z) {
        try {
            Class<?> clazz = Class.forName("net.minecraft.util.BlockPos");
            java.lang.reflect.Constructor<?> ctor = clazz.getConstructor(int.class, int.class, int.class);
            return ctor.newInstance(x, y, z);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 安全通过反射获取字段值（沿继承链向上查找，找不到再试 getter） */
    private static Object reflectGet(Object obj, String fieldName) {
        Class<?> current = obj.getClass();
        while (current != null) {
            try {
                Field f = current.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        String getter = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        current = obj.getClass();
        while (current != null) {
            try {
                Method m = current.getDeclaredMethod(getter);
                m.setAccessible(true);
                return m.invoke(obj);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
