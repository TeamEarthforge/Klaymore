package com.earthforge.klaymore.script

/** 脚本基类：所有脚本必须继承此类。字段通过反射注入（target → container → selfContainer → parent → net）。 */
abstract class KlaymoreScript {
  /** 绑定的目标对象（玩家、实体、方块、Dummy 等）。 */
  lateinit var target: Any

  /** 当前脚本容器（存数据、卸载、生成子实例等）。 */
  lateinit var container: ScriptContainer

  /** container 的别名，部分脚本习惯用 selfContainer。 */
  lateinit var selfContainer: ScriptContainer

  /** 父容器的目标对象（可能为 null）。 */
  var parent: Any? = null

  /** C/S 网络通信通道。 */
  lateinit var net: ScriptNet

  /**
   * 是否为全局脚本（不绑定具体 target）。
   *
   * 全局脚本不绑定任何实体/方块，引擎会自动分配一个 Dummy 作为 target。 默认 false；需要时覆写为 true（如 Root.kt、System 脚本等）。
   */
  open val isGlobal: Boolean = false

  /** preInit 阶段回调，此时 target/container 等字段尚未注入。用于注册物品/方块等早期内容。 */
  open fun onRegister() {}
}
