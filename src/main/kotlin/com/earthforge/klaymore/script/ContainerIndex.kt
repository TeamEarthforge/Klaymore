package com.earthforge.klaymore.script

import java.util.concurrent.ConcurrentHashMap

/** 容器索引：Key -> ScriptContainer O(1) 映射。Key 由 [PersistenceManager.generateKey] 生成。 */
object ContainerIndex {

  private val index = ConcurrentHashMap<String, ScriptContainer>()

  /** 注册容器到索引。key 通常由 PersistenceManager.generateKey(target) 生成。 */
  @JvmStatic
  fun register(key: String, container: ScriptContainer) {
    if (key.isEmpty()) return
    index[key] = container
  }

  /** 从索引移除容器（容器卸载时调用）。 */
  @JvmStatic
  fun unregister(key: String) {
    if (key.isEmpty()) return
    index.remove(key)
  }

  /** 通过 Key O(1) 查找容器。 */
  @JvmStatic fun get(key: String): ScriptContainer? = index[key]

  /** 是否存在该 Key 的容器。 */
  @JvmStatic fun contains(key: String): Boolean = index.containsKey(key)

  /** 当前索引中的所有 Key。 */
  @JvmStatic fun keys(): Set<String> = index.keys.toSet()

  /** 当前索引中的所有容器。 */
  @JvmStatic fun values(): List<ScriptContainer> = index.values.toList()

  /** 清空索引（世界卸载时调用）。 */
  @JvmStatic
  fun clear() {
    index.clear()
  }
}
