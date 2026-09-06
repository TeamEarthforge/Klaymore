package com.earthforge.klaymore.wand;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import com.earthforge.klaymore.item.ContainerWandScriptBind;

import cpw.mods.fml.common.network.IGuiHandler;

public final class WandGuiHandler implements IGuiHandler {

    public static final int GUI_ID_WAND_BIND = 1;

    @Override
    public Object getServerGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
        if (ID == GUI_ID_WAND_BIND) {
            int entityId = x;
            return new ContainerWandScriptBind(player, entityId);
        }
        return null;
    }

    @Override
    public Object getClientGuiElement(int ID, EntityPlayer player, World world, int x, int y, int z) {
        if (ID == GUI_ID_WAND_BIND) {
            int entityId = x;
            return new com.earthforge.klaymore.client.gui.GuiWandScriptBind(player, entityId);
        }
        return null;
    }

    public static void sendBindScript(EntityPlayerMP player, int entityId, String scriptName) {
        WandEventBridge.handleBindRequest(player, entityId, scriptName);
    }
}
