package com.earthforge.klaymore.wand;

import com.earthforge.klaymore.Klaymore;
import com.earthforge.klaymore.item.KlaymoreItems;
import com.earthforge.klaymore.script.PersistenceStorage;
import com.earthforge.klaymore.script.ScriptBindingManager;
import com.earthforge.klaymore.script.ScriptContainer;
import com.earthforge.klaymore.script.ScriptContainerFactory;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;

import java.io.File;
import java.util.List;

public final class WandEventBridge {

    private WandEventBridge() {}

    public static void handleBindRequest(EntityPlayerMP player, int entityId, String rawScriptName) {
        if (player == null) return;

        ItemStack held = player.getCurrentEquippedItem();
        if (held == null || held.getItem() != KlaymoreItems.klaymoreWand) {
            player.addChatMessage(new ChatComponentText(EnumChatFormatting.RED +
                "必须手持 Klaymore Wand 才能绑定脚本"));
            return;
        }

        if (player.worldObj == null) return;
        Entity target = player.worldObj.getEntityByID(entityId);
        if (target == null) {
            player.addChatMessage(new ChatComponentText(EnumChatFormatting.RED +
                "目标实体不存在或已被加载卸载"));
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

        // ======== 绑定：先校验文件存在 ========
        String normalizedName = normalizeScriptName(scriptName);
        File scriptDir = PersistenceStorage.getScriptDirectory();
        if (scriptDir == null) {
            player.addChatMessage(new ChatComponentText(EnumChatFormatting.RED +
                "无法获取脚本目录，必须先进入一个世界"));
            return;
        }
        File scriptFile = new File(scriptDir, normalizedName);
        if (!scriptFile.exists() || !scriptFile.isFile()) {
            player.addChatMessage(new ChatComponentText(EnumChatFormatting.RED +
                "脚本文件不存在: " + normalizedName + " (路径: " + scriptDir.getAbsolutePath() + ")"));
            return;
        }

        // ======== 先解绑旧的（不丢已绑定的其它脚本） ========
        unbindScriptForTarget(player, target, normalizedName);

        // ======== 调用工厂绑定 ========
        try {
            ScriptContainer container = ScriptContainerFactory.createAndMount(
                normalizedName, scriptFile, target, null);
            if (container == null) {
                player.addChatMessage(new ChatComponentText(EnumChatFormatting.RED +
                    "绑定失败：脚本编译或实例化失败，请查看日志"));
                return;
            }
        } catch (Throwable t) {
            Klaymore.LOG.error("[Klaymore Wand] bind script to entity failed: "
                + normalizedName + " -> entity " + entityId, t);
            player.addChatMessage(new ChatComponentText(EnumChatFormatting.RED +
                "绑定异常: " + t.getMessage()));
            return;
        }

        // ======== 立即持久化 ========
        try {
            PersistenceStorage.saveAll();
        } catch (Throwable ignored) {}

        player.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN +
            "脚本绑定成功: " + normalizedName));
        player.closeScreen();
    }

    private static void unbindTarget(EntityPlayerMP player, Entity target) {
        if (target == null) return;
        List<ScriptContainer> list = ScriptBindingManager.findByTarget(target);
        if (list == null || list.isEmpty()) {
            player.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW +
                "目标实体没有绑定任何 Klaymore 脚本"));
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
        player.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA +
            "已解绑 " + count + " 个脚本"));
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
        String s = raw.trim().replace('\\', '/');
        // 防穿越：去掉任何 ../
        while (s.contains("../")) {
            s = s.replace("../", "");
        }
        if (s.startsWith("/")) s = s.substring(1);
        if (!s.endsWith(".kts")) s = s + ".kts";
        return s;
    }
}
