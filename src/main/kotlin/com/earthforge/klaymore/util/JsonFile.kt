package com.earthforge.klaymore.util

import com.earthforge.klaymore.MinecraftDirectory
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/** JSON 文件读写工具，路径相对于 `<mcRoot>/klaymore/data/` */
object JsonFile {

  @PublishedApi internal val gson: Gson = GsonBuilder().setPrettyPrinting().create()

  /** 将相对路径解析为 data 目录下的绝对路径 */
  @PublishedApi
  internal fun resolve(relativePath: String): File =
      File(MinecraftDirectory.getDataDirectory(), relativePath)

  /** 从 JSON 文件读取并反序列化为 [T] */
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

  /** 将对象序列化为 JSON 写入文件，先写临时文件再原子替换 */
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
