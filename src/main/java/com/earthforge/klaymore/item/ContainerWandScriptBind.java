package com.earthforge.klaymore.item;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.earthforge.klaymore.item.KlaymoreItems;

public class ContainerWandScriptBind extends Container {

    public final EntityPlayer player;
    public final int targetEntityId;
    public Entity cachedTarget;

    public ContainerWandScriptBind(EntityPlayer player, int targetEntityId) {
        this.player = player;
        this.targetEntityId = targetEntityId;
        World world = player.worldObj;
        if (world != null) {
            this.cachedTarget = world.getEntityByID(targetEntityId);
        }
        bindPlayerInventory(player.inventory);
    }

    private void bindPlayerInventory(InventoryPlayer inv) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlotToContainer(new Slot(inv, col + row * 9 + 9, 8 + col * 18, 142 + row * 18));
            }
        }
        for (int hotbar = 0; hotbar < 9; hotbar++) {
            addSlotToContainer(new Slot(inv, hotbar, 8 + hotbar * 18, 200));
        }
    }

    public boolean hasWandInHand() {
        if (player == null) return false;
        ItemStack held = player.getCurrentEquippedItem();
        return held != null && held.getItem() == KlaymoreItems.klaymoreWand;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return hasWandInHand();
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int slotIdx) {
        ItemStack result = null;
        Slot slot = (Slot) this.inventorySlots.get(slotIdx);
        if (slot != null && slot.getHasStack()) {
            ItemStack stack = slot.getStack();
            result = stack.copy();
            if (slotIdx < 27) {
                if (!this.mergeItemStack(stack, 27, 36, false)) return null;
            } else if (!this.mergeItemStack(stack, 0, 27, false)) {
                return null;
            }
            if (stack.stackSize == 0) slot.putStack(null);
            else slot.onSlotChanged();
        }
        return result;
    }
}
