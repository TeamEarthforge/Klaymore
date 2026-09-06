package com.earthforge.klaymore.script

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import cpw.mods.fml.common.network.simpleimpl.MessageContext
import cpw.mods.fml.relauncher.Side
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.entity.Entity
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.network.NetHandlerPlayServer

/**
 * 脚本侧 C/S 通信 API。
 *
 * 通过约定方法 `bindNet(net: ScriptNet)` 注入到脚本实例。 所有方法线程安全：发送可在任意线程调用，接收 handler 统一在主线程执行。
 *
 * payload 统一为 Map<String, Any?>（嵌套 List/Map/基本类型/String）， 与 persistentData 的安全类型体系一致。
 *
 * 用法示例（客户端脚本）：
 *
 * ```
 * fun bindNet(net: ScriptNet) {
 *     net.on("announcement") { data, _ ->
 *         // data 是 Map<String, Any?>
 *     }
 * }
 * net.sendToServer("quest.update", mapOf("id" to 5))
 * ```
 */
interface ScriptNet {
  /** 客户端 → 服务端。服务端脚本里调用会报错。 */
  fun sendToServer(channel: String, data: Any?)

  /** 服务端 → 指定玩家。客户端脚本里调用会报错。 */
  fun sendToPlayer(player: EntityPlayerMP, channel: String, data: Any?)

  /** 服务端 → 所有在线玩家。 */
  fun sendToAll(channel: String, data: Any?)

  /** 服务端 → center 周围 range 格内的所有玩家。 */
  fun sendToAllAround(center: Entity, range: Double, channel: String, data: Any?)

  /**
   * 注册接收 handler。
   *
   * @param handler (data, sender) → Unit；sender 仅 C→S 时有值（发送消息的玩家），S→C 时为 null
   */
  fun on(channel: String, handler: (data: Map<String, Any?>, sender: EntityPlayerMP?) -> Unit)
}

/** 内部：某个容器注册的 handler 条目 */
private data class NetHandlerEntry(
    val container: ScriptContainer,
    val handler: (Map<String, Any?>, EntityPlayerMP?) -> Unit
)

/**
 * 脚本通信路由中心。
 *
 * 维护 channel → handler 列表的映射，负责：
 * - 入站包的 JSON 反序列化 + 主线程派发
 * - 容器卸载时自动清理其注册的所有 handler（防内存泄漏）
 * - 出站消息的 JSON 序列化 + 网络发送
 */
object ScriptNetDispatcher {

  private val gson: Gson = GsonBuilder().create()
  private val payloadType = object : TypeToken<Map<String, Any?>>() {}.type

  /** channel → handler 列表（线程安全） */
  private val routes = ConcurrentHashMap<String, MutableList<NetHandlerEntry>>()

  private val networkChannel
    get() = com.earthforge.klaymore.network.KlaymoreNetwork.CHANNEL

  // --------------------------------------------------------------------------------
  //  入站：由 ScriptMessagePacket.Handler 调用（已在主线程）
  // --------------------------------------------------------------------------------

  @JvmStatic
  fun dispatchIncoming(packet: ScriptMessagePacket, ctx: MessageContext) {
    val channel = packet.channel ?: return
    val data = deserializePayload(packet.payload)

    // C→S：sender 是发送消息的玩家；S→C：sender 为 null
    val sender: EntityPlayerMP? =
        if (ctx.side == Side.SERVER) {
          try {
            val handler = ctx.netHandler as? NetHandlerPlayServer
            handler?.playerEntity
          } catch (_: Throwable) {
            null
          }
        } else null

    val handlers = routes[channel] ?: return
    val snapshot = handlers.toList()
    for (entry in snapshot) {
      try {
        entry.handler(data, sender)
      } catch (t: Throwable) {
        ScriptErrorReporter.report(
            "脚本网络 handler 执行失败 [channel=$channel, script=${entry.container.scriptName}]: ${t.message}")
      }
    }
  }

  // --------------------------------------------------------------------------------
  //  出站：供 ScriptNet 实现调用
  // --------------------------------------------------------------------------------

  @JvmStatic
  fun sendToServer(channel: String, data: Any?) {
    if (ScriptSide.current().isServer()) {
      ScriptErrorReporter.report("sendToServer 只能在客户端脚本中调用 [channel=$channel]")
      return
    }
    val json = serializePayload(data)
    networkChannel.sendToServer(ScriptMessagePacket(channel, json, -1))
  }

  @JvmStatic
  fun sendToPlayer(player: EntityPlayerMP, channel: String, data: Any?) {
    if (ScriptSide.current().isClient()) {
      ScriptErrorReporter.report("sendToPlayer 只能在服务端脚本中调用 [channel=$channel]")
      return
    }
    val json = serializePayload(data)
    networkChannel.sendTo(ScriptMessagePacket(channel, json, player.entityId), player)
  }

  @JvmStatic
  fun sendToAll(channel: String, data: Any?) {
    if (ScriptSide.current().isClient()) {
      ScriptErrorReporter.report("sendToAll 只能在服务端脚本中调用 [channel=$channel]")
      return
    }
    val json = serializePayload(data)
    networkChannel.sendToAll(ScriptMessagePacket(channel, json, -1))
  }

  @JvmStatic
  fun sendToAllAround(center: Entity, range: Double, channel: String, data: Any?) {
    if (ScriptSide.current().isClient()) {
      ScriptErrorReporter.report("sendToAllAround 只能在服务端脚本中调用 [channel=$channel]")
      return
    }
    val json = serializePayload(data)
    val point =
        cpw.mods.fml.common.network.NetworkRegistry.TargetPoint(
            center.dimension, center.posX, center.posY, center.posZ, range)
    networkChannel.sendToAllAround(ScriptMessagePacket(channel, json, center.entityId), point)
  }

  // --------------------------------------------------------------------------------
  //  注册 / 注销
  // --------------------------------------------------------------------------------

  @JvmStatic
  fun registerHandler(
      container: ScriptContainer,
      channel: String,
      handler: (Map<String, Any?>, EntityPlayerMP?) -> Unit
  ) {
    val list = routes.getOrPut(channel) { java.util.Collections.synchronizedList(mutableListOf()) }
    list.add(NetHandlerEntry(container, handler))
  }

  /** 容器卸载时调用，移除该容器注册的所有 handler */
  @JvmStatic
  fun unregisterContainer(container: ScriptContainer) {
    for ((channel, list) in routes) {
      list.removeAll { it.container === container }
      if (list.isEmpty()) {
        routes.remove(channel)
      }
    }
  }

  // --------------------------------------------------------------------------------
  //  序列化工具
  // --------------------------------------------------------------------------------

  private fun serializePayload(data: Any?): String {
    if (data == null) return "{}"
    val map = toPayloadMap(data)
    return try {
      gson.toJson(map)
    } catch (t: Throwable) {
      ScriptErrorReporter.report("脚本网络消息序列化失败 [data=${data.javaClass.simpleName}]: ${t.message}")
      "{}"
    }
  }

  /** 把任意 data 归一化成 Map<String, Any?>。非 Map 类型包成 {"value": data} */
  private fun toPayloadMap(data: Any): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    return when (data) {
      is Map<*, *> -> {
        val result = LinkedHashMap<String, Any?>()
        for ((k, v) in data) {
          result[k?.toString() ?: "null"] = normalizeForJson(v)
        }
        result
      }
      else -> mapOf("value" to normalizeForJson(data))
    }
  }

  private fun normalizeForJson(value: Any?): Any? {
    return when (value) {
      null -> null
      is String,
      is Boolean,
      is Number,
      is Char -> value
      is List<*> -> value.map { normalizeForJson(it) }
      is Map<*, *> -> {
        val result = LinkedHashMap<String, Any?>()
        for ((k, v) in value) {
          result[k?.toString() ?: "null"] = normalizeForJson(v)
        }
        result
      }
      is Array<*> -> value.map { normalizeForJson(it) }
      else -> {
        // 非安全类型：转字符串兜底，避免 Gson 循环引用/不可序列化
        ScriptErrorReporter.report("脚本网络消息包含非安全类型 '${value.javaClass.simpleName}'，已转为字符串")
        value.toString()
      }
    }
  }

  private fun deserializePayload(json: String?): Map<String, Any?> {
    if (json == null || json.isBlank()) return emptyMap()
    return try {
      val parsed = gson.fromJson<Map<String, Any?>>(json, payloadType)
      parsed ?: emptyMap()
    } catch (t: Throwable) {
      ScriptErrorReporter.report("脚本网络消息反序列化失败: ${t.message}")
      emptyMap()
    }
  }
}

/** 绑定到特定 ScriptContainer 的 ScriptNet 实现。 通过 bindNet 注入脚本实例，on() 注册的 handler 会跟随容器生命周期自动清理。 */
internal class ScriptNetImpl(private val container: ScriptContainer) : ScriptNet {

  override fun sendToServer(channel: String, data: Any?) {
    ScriptNetDispatcher.sendToServer(channel, data)
  }

  override fun sendToPlayer(player: EntityPlayerMP, channel: String, data: Any?) {
    ScriptNetDispatcher.sendToPlayer(player, channel, data)
  }

  override fun sendToAll(channel: String, data: Any?) {
    ScriptNetDispatcher.sendToAll(channel, data)
  }

  override fun sendToAllAround(center: Entity, range: Double, channel: String, data: Any?) {
    ScriptNetDispatcher.sendToAllAround(center, range, channel, data)
  }

  override fun on(
      channel: String,
      handler: (data: Map<String, Any?>, sender: EntityPlayerMP?) -> Unit
  ) {
    ScriptNetDispatcher.registerHandler(container, channel, handler)
  }
}
