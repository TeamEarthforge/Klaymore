package com.earthforge.klaymore

import net.minecraft.entity.player.EntityPlayer
import net.minecraft.util.ChatComponentText

object KlaymoreUtils {

    fun sendMessage(player: EntityPlayer, message: String) {
        player.addChatMessage(ChatComponentText(message))
    }

    fun logDebug(message: String) {
        Klaymore.LOG.debug(message)
    }

    fun logInfo(message: String) {
        Klaymore.LOG.info(message)
    }
}
