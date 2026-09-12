package com.earthforge.klaymore.script

/** 脚本所属逻辑端：服务端只挂载 SERVER 脚本，客户端只挂载 CLIENT 脚本 */
enum class ScriptSide {
  SERVER,
  CLIENT;

  fun isServer(): Boolean = this === SERVER

  fun isClient(): Boolean = this === CLIENT

  companion object {
    /** 当前运行的逻辑端（基于调用线程，集成服务器中服务端线程返回 SERVER） */
    @JvmStatic
    fun current(): ScriptSide {
      return try {
        val side = cpw.mods.fml.common.FMLCommonHandler.instance().effectiveSide
        if (side == cpw.mods.fml.relauncher.Side.CLIENT) CLIENT else SERVER
      } catch (_: Throwable) {
        SERVER
      }
    }

    /** 从脚本文件路径推断 side */
    @JvmStatic
    fun fromPath(scriptFile: java.io.File): ScriptSide {
      val parent = scriptFile.parentFile?.name?.lowercase() ?: return SERVER
      return if (parent == "client") CLIENT else SERVER
    }
  }
}
