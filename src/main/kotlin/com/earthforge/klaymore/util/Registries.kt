package com.earthforge.klaymore.util

import com.earthforge.klaymore.Klaymore
import cpw.mods.fml.common.registry.GameRegistry
import net.minecraft.block.Block
import net.minecraft.item.Item

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

  /** 注册方块。name 为注册名，返回注册后的方块实例。 */
  @JvmStatic
  fun registerBlock(name: String, block: Block): Block {
    GameRegistry.registerBlock(block, name)
    println("[Klaymore Registries] Registered block: $name -> ${block.javaClass.simpleName}")
    return block
  }

  /** 按注册名查找方块（不含 modid 前缀）。 */
  @JvmStatic
  fun getBlock(name: String): Block? = GameRegistry.findBlock(Klaymore.MODID, name) as? Block
}
