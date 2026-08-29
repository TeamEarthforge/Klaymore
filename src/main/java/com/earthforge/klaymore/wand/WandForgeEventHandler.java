package com.earthforge.klaymore.wand;

import com.earthforge.klaymore.Klaymore;
import com.earthforge.klaymore.item.KlaymoreItems;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.EntityInteractEvent;

public final class WandForgeEventHandler {

    private static boolean registered = false;

    private WandForgeEventHandler() {}

    public static void register() {
        if (registered) return;
        registered = true;
        WandForgeEventHandler h = new WandForgeEventHandler();
        MinecraftForge.EVENT_BUS.register(h);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onEntityInteract(EntityInteractEvent event) {
        if (event == null) return;
        if (event.isCanceled()) return;

        EntityPlayer player = event.entityPlayer;
        Entity target = event.target;
        if (player == null || target == null) return;
        if (FMLCommonHandler.instance().getEffectiveSide() != Side.SERVER) return;

        ItemStack held = player.getCurrentEquippedItem();
        if (held == null || held.getItem() != KlaymoreItems.klaymoreWand) return;

        event.setCanceled(true);

        int entityId = target.getEntityId();
        player.openGui(Klaymore.instance,
            WandGuiHandler.GUI_ID_WAND_BIND,
            player.worldObj, entityId, 0, 0);
    }
}
