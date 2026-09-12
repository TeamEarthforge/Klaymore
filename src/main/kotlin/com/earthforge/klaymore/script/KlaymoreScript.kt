package com.earthforge.klaymore.script

/**
 * 脚本基类：所有脚本必须继承此类。
 *
 * 设计目标：
 * - 用类型继承替代"约定字段名猜测"来判定脚本实例。
 * - 解决 Kotlin `object` 单例导致的多实例字段互相覆盖问题： 每次 `spawnChild` 都会 `newInstance()` 出全新的 `KlaymoreScript`
 *   子类实例。
 * - 提供 IDE 代码提示：target / container / net 等字段在基类中声明， 子类直接可见，无需靠 `lateinit var` 顶层声明触发 IDE 报错。
 *
 * 字段注入由引擎通过反射完成（见 ScriptInjectionUtils.injectFields）， 注入顺序：target → container → selfContainer →
 * parent → net。
 */
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

  /**
   * 注册阶段回调（preInit 阶段执行）。
   *
   * 只有 common/ 目录下的脚本会在 preInit 阶段被实例化并调用此方法， 用于注册物品、方块等必须在游戏初始化早期完成的内容。
   *
   * 此方法执行时 target / container 等字段尚未注入，不要依赖它们。 可通过 [com.earthforge.klaymore.util.Registries]
   * 完成物品/方块注册。
   */
  open fun onRegister() {}
}
