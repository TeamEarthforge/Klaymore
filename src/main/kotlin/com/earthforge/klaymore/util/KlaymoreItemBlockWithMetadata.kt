package com.earthforge.klaymore.util

import cpw.mods.fml.relauncher.Side
import cpw.mods.fml.relauncher.SideOnly
import net.minecraft.block.Block
import net.minecraft.item.ItemBlock
import net.minecraft.util.IIcon

/**
 * Klaymore 专用的带元数据 ItemBlock。
 *
 * Forge 1.7.10 内置的 [net.minecraft.item.ItemBlockWithMetadata] 构造函数为 `(Block, Block)`，而
 * `GameRegistry.registerBlock` 内部通过 `itemCtorArgs[i].getClass()` 取运行时类型并用 `getConstructor(...)`
 * 精确匹配。当方块是 `Block` 的子类时（脚本中 几乎总是如此），第二个参数的运行时类型不是 `Block` 本身，导致 `NoSuchMethodException`。
 *
 * 本类只接收一个 `Block` 参数（与 [ItemBlock] 的构造函数签名一致），因此 Forge 查找 `getConstructor(Block.class)` 时可以精确匹配。物品的
 * damage 直接映射为方块的 metadata，图标通过方块自身的 `getIcon(side, meta)` 获取。
 */
class KlaymoreItemBlockWithMetadata(private val owningBlock: Block) : ItemBlock(owningBlock) {

  init {
    maxDamage = 0
    hasSubtypes = true
  }

  @SideOnly(Side.CLIENT)
  override fun getIconFromDamage(meta: Int): IIcon? {
    return this.owningBlock.getIcon(2, meta)
  }

  override fun getMetadata(damage: Int): Int {
    return damage
  }
}
