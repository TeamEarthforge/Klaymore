package com.earthforge.klaymore.item;

import com.earthforge.klaymore.Klaymore;

import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

public final class KlaymoreItems {

    public static Item klaymoreWand;

    public static final CreativeTabs TAB_KLAYMORE = new CreativeTabs("tabKlaymore") {
        @Override
        @SideOnly(Side.CLIENT)
        public Item getTabIconItem() {
            return klaymoreWand != null ? klaymoreWand : net.minecraft.init.Items.iron_sword;
        }
    };

    private KlaymoreItems() {}

    public static void registerAll() {
        klaymoreWand = new ItemKlaymoreWand()
            .setUnlocalizedName("klaymore_wand")
            .setCreativeTab(TAB_KLAYMORE);
        GameRegistry.registerItem(klaymoreWand, "klaymore_wand", Klaymore.MODID);
    }
}
