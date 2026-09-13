package com.earthforge.klaymore.util

import com.earthforge.klaymore.Klaymore
import cpw.mods.fml.common.registry.GameRegistry
import net.minecraft.block.Block
import net.minecraft.item.Item
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemBlockWithMetadata

/** 物品/方块注册 API，供 common/ 脚本在 onRegister 阶段调用 */
object Registries {

  /** 注册物品。name 为注册名（不含 modid 前缀），返回注册后的物品实例。 */
  @JvmStatic
  fun registerItem(name: String, item: Item): Item {
    GameRegistry.registerItem(item, name, Klaymore.MODID)
    println(
        "[Klaymore Registries] Registered item: ${Klaymore.MODID}:$name -> ${item.javaClass.simpleName}")
    return item
  }

  /** 按注册名查找物品（不含 modid 前缀）。 */
  @JvmStatic fun getItem(name: String): Item? = GameRegistry.findItem(Klaymore.MODID, name)

  /** 注册方块（使用默认 ItemBlock，不支持元数据）。name 为注册名，返回注册后的方块实例。 */
  @JvmStatic
  fun registerBlock(name: String, block: Block): Block {
    GameRegistry.registerBlock(block, name)
    println("[Klaymore Registries] Registered block: $name -> ${block.javaClass.simpleName}")
    return block
  }

  /**
   * 注册方块，使用自定义 ItemBlock 类（支持元数据方块）。
   *
   * @param name 注册名（不含 modid 前缀）
   * @param block 方块实例
   * @param itemBlockClass ItemBlock 子类，如 [ItemBlockWithMetadata::class.java]
   * @param itemCtorArgs 传给 ItemBlock 构造函数的额外参数（第一个 Block 参数由 Forge 自动传入）
   */
  @JvmStatic
  fun registerBlock(
      name: String,
      block: Block,
      itemBlockClass: Class<out ItemBlock>,
      vararg itemCtorArgs: Any
  ): Block {
    GameRegistry.registerBlock(block, itemBlockClass, name, Klaymore.MODID, *itemCtorArgs)
    println(
        "[Klaymore Registries] Registered block: ${Klaymore.MODID}:$name " +
            "(item=${itemBlockClass.simpleName}) -> ${block.javaClass.simpleName}")
    return block
  }

  /**
   * 注册带元数据的方块（使用 Forge 内置的 [ItemBlockWithMetadata]）。
   *
   * 方块需覆写 `func_149691_a(side, meta)`（getIcon）返回不同 metadata 对应的图标。
   */
  @JvmStatic
  fun registerBlockWithMetadata(name: String, block: Block): Block {
    return registerBlock(name, block, ItemBlockWithMetadata::class.java, block)
  }

  /** 按注册名查找方块（不含 modid 前缀）。 */
  @JvmStatic
  fun getBlock(name: String): Block? = GameRegistry.findBlock(Klaymore.MODID, name) as? Block
}
