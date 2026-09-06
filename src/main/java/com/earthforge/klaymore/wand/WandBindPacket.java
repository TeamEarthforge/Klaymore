package com.earthforge.klaymore.wand;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

public final class WandBindPacket implements IMessage {

    public int entityId;
    public String scriptName;

    public WandBindPacket() {}

    public WandBindPacket(int entityId, String scriptName) {
        this.entityId = entityId;
        this.scriptName = scriptName == null ? "" : scriptName.trim();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        entityId = buf.readInt();
        scriptName = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
        ByteBufUtils.writeUTF8String(buf, scriptName == null ? "" : scriptName);
    }

    public static final class Handler implements IMessageHandler<WandBindPacket, IMessage> {

        @Override
        public IMessage onMessage(WandBindPacket msg, MessageContext ctx) {
            if (ctx.side != Side.SERVER) return null;
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            WandEventBridge.handleBindRequest(player, msg.entityId, msg.scriptName);
            return null;
        }
    }
}
