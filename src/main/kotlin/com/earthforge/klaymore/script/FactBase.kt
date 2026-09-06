package com.earthforge.klaymore.script

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 事实库（FactBase）：三元组 (Subject, Predicate, Object) 存储。
 *
 * 设计目标（见 Klaymore 方案 §2.3 / §4）：
 * - 弃用广播式事件，改为基于匹配的解耦通信。
 * - System 脚本写入 (棋子UUID, "cmd.move", 坐标)，棋子脚本订阅特定 Predicate 并检查 Subject 是否匹配自身。
 * - 存储结构：Map<Subject, Map<Predicate, Object>>，O(1) 定位。
 *
 * 线程安全：使用 ConcurrentHashMap，put/get 可在任意线程调用。 持久化：支持 JSON 序列化（可选），替代"恢复树"逻辑。
 */
object FactBase {

  private val gson: Gson = GsonBuilder().create()
  private val mapType = object : TypeToken<Map<String, Map<String, Any?>>>() {}.type

  /** subject -> (predicate -> object) */
  private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Any?>>()

  // --------------------------------------------------------------------------------
  //  写入
  // --------------------------------------------------------------------------------

  /** 写入一条事实：(subject, predicate, obj)。覆盖同一 (subject, predicate) 的旧值。 */
  @JvmStatic
  fun put(subject: String, predicate: String, obj: Any?) {
    if (subject.isEmpty() || predicate.isEmpty()) return
    store.getOrPut(subject) { ConcurrentHashMap() }.put(predicate, obj)
  }

  /** 删除某 subject 下某 predicate 的事实。 */
  @JvmStatic
  fun remove(subject: String, predicate: String) {
    store[subject]?.remove(predicate)
  }

  /** 删除某 subject 的所有事实。 */
  @JvmStatic
  fun removeSubject(subject: String) {
    store.remove(subject)
  }

  // --------------------------------------------------------------------------------
  //  查询
  // --------------------------------------------------------------------------------

  /** 查询 (subject, predicate) 对应的 object。 */
  @JvmStatic fun get(subject: String, predicate: String): Any? = store[subject]?.get(predicate)

  /** 查询某 subject 的所有 (predicate, object)。 */
  @JvmStatic
  fun getSubject(subject: String): Map<String, Any?> = store[subject]?.toMap() ?: emptyMap()

  /**
   * 查询所有包含某 predicate 的事实。 返回 List<Triple(subject, predicate, object)>。 用于棋子脚本订阅特定 Predicate 后，遍历检查
   * Subject 是否匹配自身。
   */
  @JvmStatic
  fun queryByPredicate(predicate: String): List<Triple<String, String, Any?>> {
    val result = mutableListOf<Triple<String, String, Any?>>()
    for ((subject, predMap) in store) {
      val obj = predMap[predicate]
      if (obj != null || predMap.containsKey(predicate)) {
        result.add(Triple(subject, predicate, obj))
      }
    }
    return result
  }

  /** 查询所有匹配 (subject, predicate) 的事实（subject 支持前缀匹配）。 */
  @JvmStatic
  fun queryBySubjectPrefix(prefix: String): List<Triple<String, String, Any?>> {
    val result = mutableListOf<Triple<String, String, Any?>>()
    for ((subject, predMap) in store) {
      if (subject.startsWith(prefix)) {
        for ((predicate, obj) in predMap) {
          result.add(Triple(subject, predicate, obj))
        }
      }
    }
    return result
  }

  /** 是否存在某条事实。 */
  @JvmStatic
  fun contains(subject: String, predicate: String): Boolean =
      store[subject]?.containsKey(predicate) ?: false

  // --------------------------------------------------------------------------------
  //  生命周期
  // --------------------------------------------------------------------------------

  /** 清空所有事实（世界卸载时调用）。 */
  @JvmStatic
  fun clear() {
    store.clear()
  }

  /** 事实总数（subject 数量）。 */
  @JvmStatic fun subjectCount(): Int = store.size

  // --------------------------------------------------------------------------------
  //  序列化（可选持久化）
  // --------------------------------------------------------------------------------

  /** 序列化为 JSON 字符串（用于落盘或网络传输）。 */
  @JvmStatic
  fun toJson(): String {
    val snapshot = LinkedHashMap<String, Map<String, Any?>>()
    for ((subject, predMap) in store) {
      snapshot[subject] = predMap.toMap()
    }
    return gson.toJson(snapshot)
  }

  /** 从 JSON 字符串反序列化（覆盖现有数据）。 */
  @JvmStatic
  fun fromJson(json: String) {
    clear()
    if (json.isBlank()) return
    try {
      val parsed = gson.fromJson<Map<String, Map<String, Any?>>>(json, mapType) ?: return
      for ((subject, predMap) in parsed) {
        val inner = ConcurrentHashMap<String, Any?>()
        inner.putAll(predMap)
        store[subject] = inner
      }
    } catch (t: Throwable) {
      ScriptErrorReporter.report("FactBase.fromJson 反序列化失败: ${t.message}")
    }
  }

  /** 保存到文件。 */
  @JvmStatic
  fun saveToFile(file: File) {
    try {
      file.bufferedWriter(Charsets.UTF_8).use { it.write(toJson()) }
    } catch (t: Throwable) {
      ScriptErrorReporter.report("FactBase.saveToFile 失败: ${t.message}")
    }
  }

  /** 从文件加载（覆盖现有数据）。 */
  @JvmStatic
  fun loadFromFile(file: File) {
    if (!file.exists() || !file.isFile) return
    try {
      fromJson(file.bufferedReader(Charsets.UTF_8).readText())
    } catch (t: Throwable) {
      ScriptErrorReporter.report("FactBase.loadFromFile 失败: ${t.message}")
    }
  }
}
