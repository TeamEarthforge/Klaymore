package com.earthforge.klaymore.item;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import java.util.List;

public class ItemKlaymoreWand extends Item {

    public ItemKlaymoreWand() {
        this.maxStackSize = 1;
        this.setFull3D();
        this.setMaxDamage(0);
    }

    @Override
    public boolean itemInteractionForEntity(ItemStack stack, EntityPlayer player, EntityLivingBase target) {
        if (player == null || target == null || player.worldObj == null) return false;
        if (!player.worldObj.isRemote) {
            if (target instanceof net.minecraft.entity.Entity) {
                int entityId = target.getEntityId();
                player.openGui(com.earthforge.klaymore.Klaymore.instance,
                    com.earthforge.klaymore.wand.WandGuiHandler.GUI_ID_WAND_BIND,
                    player.worldObj, entityId, 0, 0);
                return true;
            }
        }
        return true;
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        return stack;
    }

    @Override
    public boolean getIsRepairable(ItemStack toRepair, ItemStack repair) {
        return false;
    }

    @Override
    public int getColorFromItemStack(ItemStack stack, int pass) {
        return 0x3399FF;
    }

    @Override
    public boolean requiresMultipleRenderPasses() {
        return true;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void registerIcons(IIconRegister reg) {
        this.itemIcon = Items.iron_sword.getIconFromDamage(0);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltipList, boolean advanced) {
        tooltipList.add(EnumChatFormatting.AQUA + StatCollector.translateToLocal("tooltip.klaymore_wand.line1"));
        tooltipList.add(EnumChatFormatting.GRAY + StatCollector.translateToLocal("tooltip.klaymore_wand.line2"));
    }
}
