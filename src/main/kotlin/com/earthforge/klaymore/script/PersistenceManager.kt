package com.earthforge.klaymore.script

import com.earthforge.klaymore.EntityHelper
import java.util.UUID
import net.minecraft.entity.Entity
import net.minecraft.server.MinecraftServer

object PersistenceManager {
  private val registry = mutableMapOf<Class<*>, PersistenceAdapter<*>>()
  private val prefixToAdapter = mutableMapOf<String, PersistenceAdapter<*>>()

  init {
    register("entity", EntityAdapter())
    register("block", BlockPosAdapter())
    register("dummy", DummyAdapter())
  }

  @JvmStatic
  fun register(prefix: String, adapter: PersistenceAdapter<*>) {
    registry[adapter.getType()] = adapter
    prefixToAdapter[prefix] = adapter
  }

  @JvmStatic
  fun register(adapter: PersistenceAdapter<*>) {
    registry[adapter.getType()] = adapter
  }

  @JvmStatic
  fun generateKey(target: Any): String? {
    val targetClass = target.javaClass
    var bestMatch: PersistenceAdapter<*>? = null
    var bestDistance = Int.MAX_VALUE

    for ((clazz, adapter) in registry) {
      if (clazz.isInstance(target)) {
        val distance = classDistance(targetClass, clazz)
        if (distance < bestDistance) {
          bestDistance = distance
          bestMatch = adapter
        }
      }
    }

    return if (bestMatch != null) {
      @Suppress("UNCHECKED_CAST") (bestMatch as PersistenceAdapter<Any>).generateKey(target)
    } else null
  }

  @JvmStatic
  fun resolve(key: String): Any? {
    val prefix = key.substringBefore(':')
    val adapter = prefixToAdapter[prefix] ?: return null
    val rest = key.substringAfter(':', "")
    @Suppress("UNCHECKED_CAST")
    return (adapter as PersistenceAdapter<Any>).resolve(rest)
  }

  private fun classDistance(derived: Class<*>, base: Class<*>): Int {
    if (derived == base) return 0
    var current: Class<*>? = derived.superclass
    var distance = 1
    while (current != null) {
      if (current == base) return distance
      current = current.superclass
      distance++
    }
    return Int.MAX_VALUE
  }
}

class EntityAdapter : PersistenceAdapter<Entity> {
  override fun getType(): Class<Entity> = Entity::class.java

  override fun generateKey(instance: Entity): String = "entity:${instance.uniqueID}"

  override fun resolve(key: String): Entity? {
    return try {
      val uuid = UUID.fromString(key)
      val server = MinecraftServer.getServer() ?: return null
      if (server.worldServers == null) return null
      for (world in server.worldServers) {
        if (world == null) continue
        val entity = EntityHelper.getEntityByUUID(world, uuid)
        if (entity != null) return entity
      }
      null
    } catch (_: IllegalArgumentException) {
      null
    }
  }
}

class BlockPosAdapter : PersistenceAdapter<BlockPosKey> {
  override fun getType(): Class<BlockPosKey> = BlockPosKey::class.java

  override fun generateKey(instance: BlockPosKey): String =
      "block:${instance.dim}:${instance.x}:${instance.y}:${instance.z}"

  override fun resolve(key: String): BlockPosKey? {
    val parts = key.split(':')
    if (parts.size != 4) return null
    return try {
      BlockPosKey(
          dim = parts[0].toInt(), x = parts[1].toInt(), y = parts[2].toInt(), z = parts[3].toInt())
    } catch (_: NumberFormatException) {
      null
    }
  }
}

data class BlockPosKey(val dim: Int, val x: Int, val y: Int, val z: Int)

class DummyAdapter : PersistenceAdapter<Dummy> {
  override fun getType(): Class<Dummy> = Dummy::class.java

  override fun generateKey(instance: Dummy): String = "dummy:${instance.id}"

  override fun resolve(key: String): Dummy {
    if ("root" == key) {
      return GlobalRoot.target
    }
    if ("client_root" == key) {
      return GlobalRoot.clientTarget
    }
    return Dummy(key)
  }
}
