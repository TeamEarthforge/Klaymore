package com.earthforge.klaymore.script

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.gameevent.PlayerEvent
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.UUID
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.server.MinecraftServer

object PersistenceStorage {
  private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
  private const val BINDINGS_FILE = "bindings.json"
  private const val SCRIPT_DIR = "klaymore"

  private var cachedBindings: Map<String, BindingEntry> = emptyMap()
  private var boundKeys: MutableSet<String> = mutableSetOf()

  private data class BindingEntry(val script: String, val data: Map<String, *>)

  init {
    FMLCommonHandler.instance().bus().register(this)
  }

  private fun getBindingsFile(): File? {
    val server = MinecraftServer.getServer() ?: return null
    val worldDir =
        server.entityWorld?.saveHandler?.worldDirectory
            ?: run {
              val worlds = server.worldServers
              if (worlds != null && worlds.isNotEmpty()) {
                worlds[0]?.saveHandler?.worldDirectory
              } else null
            }
            ?: return null
    val klaymoreDir = File(worldDir, SCRIPT_DIR)
    if (!klaymoreDir.exists()) klaymoreDir.mkdirs()
    return File(klaymoreDir, BINDINGS_FILE)
  }

  @JvmStatic
  fun getScriptDirectory(): File? {
    val server = MinecraftServer.getServer() ?: return null
    val worldDir =
        server.entityWorld?.saveHandler?.worldDirectory
            ?: run {
              val worlds = server.worldServers
              if (worlds != null && worlds.isNotEmpty()) {
                worlds[0]?.saveHandler?.worldDirectory
              } else null
            }
            ?: return null
    val klaymoreDir = File(worldDir, SCRIPT_DIR)
    if (!klaymoreDir.exists()) klaymoreDir.mkdirs()
    return klaymoreDir
  }

  @JvmStatic
  fun saveAll() {
    val bindingsFile = getBindingsFile() ?: return
    val entries = linkedMapOf<String, BindingEntry>()

    for (container in ScriptBindingManager.getContainers()) {
      val target = container.getTarget() ?: continue
      val key = PersistenceManager.generateKey(target) ?: continue
      entries[key] = BindingEntry(container.scriptName, container.exportPersistentData())
    }

    for ((key, entry) in cachedBindings) {
      if (key !in entries) {
        entries[key] = entry
      }
    }

    try {
      FileWriter(bindingsFile).use { writer -> gson.toJson(entries, writer) }
      ScriptErrorReporter.report("保存了 ${entries.size} 个脚本绑定到 ${bindingsFile.absolutePath}")
    } catch (e: Exception) {
      ScriptErrorReporter.report("保存绑定数据失败: ${e.message}")
    }
  }

  @JvmStatic
  fun loadAll() {
    val bindingsFile = getBindingsFile() ?: return
    boundKeys.clear()

    if (!bindingsFile.exists()) {
      cachedBindings = emptyMap()
      return
    }

    val type = object : TypeToken<Map<String, BindingEntry>>() {}.type
    cachedBindings =
        try {
          FileReader(bindingsFile).use { reader ->
            val loaded: Map<String, BindingEntry> = gson.fromJson(reader, type) ?: emptyMap()
            loaded
          }
        } catch (e: Exception) {
          ScriptErrorReporter.report("加载绑定数据失败: ${e.message}")
          emptyMap()
        }

    ScriptErrorReporter.report("从磁盘读取了 ${cachedBindings.size} 个绑定条目")

    val scriptDir = getScriptDirectory()
    for ((key, entry) in cachedBindings) {
      tryBindEntry(key, entry, scriptDir)
    }
  }

  private fun tryBindEntry(key: String, entry: BindingEntry, scriptDir: File?): Boolean {
    if (key in boundKeys) return true

    val target = PersistenceManager.resolve(key)
    if (target == null) {
      ScriptErrorReporter.report("暂无法解析目标 [$key]（如玩家离线），已保留条目待稍后重试")
      return false
    }

    if (scriptDir == null) {
      ScriptErrorReporter.report("无法确定脚本目录，跳过绑定 [$key]")
      return false
    }

    val scriptFile = File(scriptDir, entry.script)
    if (!scriptFile.exists()) {
      ScriptErrorReporter.report("脚本文件不存在: ${scriptFile.absolutePath}，跳过绑定 [$key]")
      return false
    }

    val container =
        ScriptContainerFactory.createAndMount(
            scriptName = entry.script, scriptFile = scriptFile, target = target)

    if (container == null) {
      ScriptErrorReporter.report("创建容器失败，跳过绑定 [$key] -> ${entry.script}")
      return false
    }

    try {
      container.importPersistentData(entry.data)
    } catch (e: Exception) {
      ScriptErrorReporter.report("导入持久化数据失败 [$key]: ${e.message}")
    }

    boundKeys.add(key)
    ScriptErrorReporter.report("成功恢复绑定 [$key] -> ${entry.script}")
    return true
  }

  @SubscribeEvent
  fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
    val player: EntityPlayer = event.player ?: return
    val playerKey = "entity:${player.uniqueID}"
    ScriptErrorReporter.report("玩家登录，检查延迟绑定: $playerKey")
    val scriptDir = getScriptDirectory()
    for ((key, entry) in cachedBindings) {
      if (key in boundKeys) continue
      if (matchesPlayer(key, player.uniqueID)) {
        tryBindEntry(key, entry, scriptDir)
      }
    }
  }

  private fun matchesPlayer(key: String, playerUuid: UUID): Boolean {
    if (!key.startsWith("entity:")) return false
    val uuidPart = key.substringAfter("entity:")
    return try {
      UUID.fromString(uuidPart) == playerUuid
    } catch (_: IllegalArgumentException) {
      false
    }
  }
}
