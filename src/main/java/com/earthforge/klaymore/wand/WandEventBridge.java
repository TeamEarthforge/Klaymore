package com.earthforge.klaymore.wand;

import java.io.File;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.earthforge.klaymore.Klaymore;
import com.earthforge.klaymore.item.KlaymoreItems;
import com.earthforge.klaymore.script.PersistenceStorage;
import com.earthforge.klaymore.script.ScriptBindingManager;
import com.earthforge.klaymore.script.ScriptContainer;
import com.earthforge.klaymore.script.ScriptContainerFactory;

public final class WandEventBridge {

    private WandEventBridge() {}

    public static void handleBindRequest(EntityPlayerMP player, int entityId, String rawScriptName) {
        if (player == null) return;

        ItemStack held = player.getCurrentEquippedItem();
        if (held == null || held.getItem() != KlaymoreItems.klaymoreWand) {
            player.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "必须手持 Klaymore Wand 才能绑定脚本"));
            return;
        }

        if (player.worldObj == null) return;
        Entity target = player.worldObj.getEntityByID(entityId);
        if (target == null) {
            player.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "目标实体不存在或已被加载卸载"));
            return;
        }

        String scriptName = rawScriptName == null ? "" : rawScriptName.trim();

        // ======== 解绑：脚本名为空 ========
        if (scriptName.isEmpty()) {
            unbindTarget(player, target);
            // 关闭 GUI
            player.closeScreen();
            return;
        }

        // ======== 绑定：先校验文件存在（全局优先 + 存档 fallback） ========
        String normalizedName = normalizeScriptName(scriptName);
        File globalDir = PersistenceStorage.getScriptDirectory();
        File scriptFile = PersistenceStorage.resolveScriptFile(normalizedName);
        if (scriptFile == null || !scriptFile.exists() || !scriptFile.isFile()) {
            String where = (globalDir != null) ? globalDir.getAbsolutePath() : "<unknown>";
            player.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.RED + "脚本文件不存在: " + normalizedName + " (全局脚本目录: " + where + ")"));
            return;
        }

        // ======== 先解绑旧的（不丢已绑定的其它脚本） ========
        unbindScriptForTarget(player, target, normalizedName);

        // ======== 异步编译 + 绑定（不阻塞主线程）========
        final String fNormalizedName = normalizedName;
        final Entity fTarget = target;
        player.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "正在编译脚本: " + normalizedName + " ..."));

        try {
            ScriptContainerFactory.createAndMountAsync(
                fNormalizedName,
                scriptFile,
                fTarget,
                null,
                new java.util.function.Consumer<ScriptContainer>() {

                    @Override
                    public void accept(ScriptContainer container) {
                        if (container == null) {
                            player.addChatMessage(
                                new ChatComponentText(EnumChatFormatting.RED + "绑定失败：脚本编译或实例化失败，请查看日志"));
                            return;
                        }
                        // ======== 编译成功 → 立即持久化 ========
                        try {
                            PersistenceStorage.saveAll();
                        } catch (Throwable ignored) {}

                        player.addChatMessage(
                            new ChatComponentText(EnumChatFormatting.GREEN + "脚本绑定成功: " + fNormalizedName));
                        player.closeScreen();
                    }
                });
        } catch (Throwable t) {
            Klaymore.LOG.error(
                "[Klaymore Wand] submit async bind task failed: " + normalizedName + " -> entity " + entityId,
                t);
            player.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "启动异步编译失败: " + t.getMessage()));
        }
    }

    private static void unbindTarget(EntityPlayerMP player, Entity target) {
        if (target == null) return;
        List<ScriptContainer> list = ScriptBindingManager.findByTarget(target);
        if (list == null || list.isEmpty()) {
            player.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "目标实体没有绑定任何 Klaymore 脚本"));
            return;
        }
        int count = 0;
        for (ScriptContainer c : list) {
            try {
                ScriptContainerFactory.unmount(c);
                count++;
            } catch (Throwable t) {
                Klaymore.LOG.error("[Klaymore Wand] unmount container error: " + t.getMessage());
            }
        }
        try {
            PersistenceStorage.saveAll();
        } catch (Throwable ignored) {}
        player.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "已解绑 " + count + " 个脚本"));
    }

    private static void unbindScriptForTarget(EntityPlayerMP player, Entity target, String scriptName) {
        List<ScriptContainer> list = ScriptBindingManager.findByTarget(target);
        if (list == null) return;
        for (ScriptContainer c : list) {
            if (scriptName.equals(c.getScriptName())) {
                try {
                    ScriptContainerFactory.unmount(c);
                } catch (Throwable t) {
                    Klaymore.LOG.warn("[Klaymore Wand] unmount old container: " + t.getMessage());
                }
            }
        }
    }

    private static String normalizeScriptName(String raw) {
        String s = raw.trim()
            .replace('\\', '/');
        // 防穿越：去掉任何 ../
        while (s.contains("../")) {
            s = s.replace("../", "");
        }
        if (s.startsWith("/")) s = s.substring(1);
        if (!s.endsWith(".kt")) s = s + ".kt";
        return s;
    }
}
