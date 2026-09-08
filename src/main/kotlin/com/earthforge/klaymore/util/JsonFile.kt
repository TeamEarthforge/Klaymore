package com.earthforge.klaymore.util

import com.earthforge.klaymore.MinecraftDirectory
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * JSON 文件读写工具。
 *
 * 封装 Gson + 文件 IO 的样板代码，一行完成「读 JSON → 对象」或「对象 → 写 JSON」。
 *
 * 所有路径相对于 Klaymore 数据目录（`<mcRoot>/klaymore/data/`）解析， 脚本无需关心绝对路径或目录创建。
 *
 * 用法：
 *
 * ```kotlin
 * data class Config(val name: String, val level: Int)
 *
 * // 读取（文件不存在返回 null）
 * val config: Config? = JsonFile.load("config/my_config.json")
 *
 * // 读取并带默认值
 * val config = JsonFile.loadOr("config/my_config.json", Config("default", 1))
 *
 * // 保存
 * JsonFile.save("config/my_config.json", config)
 * ```
 */
object JsonFile {

  @PublishedApi internal val gson: Gson = GsonBuilder().setPrettyPrinting().create()

  /** 将相对路径解析为 data 目录下的绝对 File。 */
  @PublishedApi
  internal fun resolve(relativePath: String): File =
      File(MinecraftDirectory.getDataDirectory(), relativePath)

  /**
   * 从 JSON 文件读取并反序列化为 [T]。
   *
   * @param relativePath 相对于 data 目录的路径，如 "config/foo.json"
   * @return 反序列化后的对象；文件不存在或解析失败返回 null
   */
  inline fun <reified T> load(relativePath: String): T? {
    val file = resolve(relativePath)
    if (!file.exists() || !file.isFile) return null
    return try {
      file.bufferedReader(Charsets.UTF_8).use { reader -> gson.fromJson(reader, T::class.java) }
    } catch (e: Exception) {
      System.err.println("[Klaymore JsonFile] Failed to load $relativePath: ${e.message}")
      null
    }
  }

  /** 从 JSON 文件读取并反序列化为 [T]，失败时返回 [default]。 */
  inline fun <reified T> loadOr(relativePath: String, default: T): T =
      load<T>(relativePath) ?: default

  /**
   * 将对象序列化为 JSON 并写入文件。
   *
   * 会自动创建父目录。写入采用「先写临时文件再原子替换」策略，避免写入中途崩溃导致文件损坏。
   *
   * @param relativePath 相对于 data 目录的路径
   * @return 写入成功返回 true
   */
  fun save(relativePath: String, obj: Any?): Boolean {
    val file = resolve(relativePath)
    return try {
      file.parentFile?.mkdirs()
      val tmp = File(file.parentFile, file.name + ".tmp")
      tmp.bufferedWriter(Charsets.UTF_8).use { writer -> gson.toJson(obj, writer) }
      // 原子替换（Windows 下 renameTo 可能失败，回退到删除再重命名）
      if (!tmp.renameTo(file)) {
        file.delete()
        if (!tmp.renameTo(file)) {
          // 最终回退：直接覆盖写
          file.bufferedWriter(Charsets.UTF_8).use { writer -> gson.toJson(obj, writer) }
        }
      }
      true
    } catch (e: Exception) {
      System.err.println("[Klaymore JsonFile] Failed to save $relativePath: ${e.message}")
      false
    }
  }

  /** 从 JSON 字符串反序列化为 [T]（不涉及文件）。 */
  inline fun <reified T> fromJson(json: String): T? =
      try {
        gson.fromJson(json, T::class.java)
      } catch (e: Exception) {
        null
      }

  /** 将对象序列化为 JSON 字符串（不涉及文件）。 */
  fun toJson(obj: Any?): String = gson.toJson(obj)
}
