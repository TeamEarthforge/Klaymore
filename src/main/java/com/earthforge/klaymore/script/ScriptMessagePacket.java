package com.earthforge.klaymore.script;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 脚本间通用通信包。
 *
 * 所有脚本 C/S 通信复用这一个包，通过 channel 字段区分逻辑通道，
 * payload 是 Gson 序列化后的 JSON（Map<String, Object> / List / 基本类型 / String）。
 * 脚本侧不需要定义新的 IMessage 类，直接用 ScriptNet.send* / ScriptNet.on 即可。
 */
public final class ScriptMessagePacket implements IMessage {

    public String channel;
    public String payload;
    public int targetEntityId;

    public ScriptMessagePacket() {
        this.channel = "";
        this.payload = "{}";
        this.targetEntityId = -1;
    }

    public ScriptMessagePacket(String channel, String payload, int targetEntityId) {
        this.channel = channel == null ? "" : channel;
        this.payload = payload == null ? "{}" : payload;
        this.targetEntityId = targetEntityId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        channel = ByteBufUtils.readUTF8String(buf);
        payload = ByteBufUtils.readUTF8String(buf);
        targetEntityId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, channel == null ? "" : channel);
        ByteBufUtils.writeUTF8String(buf, payload == null ? "{}" : payload);
        buf.writeInt(targetEntityId);
    }

    public static final class Handler implements IMessageHandler<ScriptMessagePacket, IMessage> {

        @Override
        public IMessage onMessage(final ScriptMessagePacket msg, final MessageContext ctx) {
            if (msg == null) return null;
            // Netty 线程 → 切到主线程再派发，脚本 handler 可安全操作世界/玩家
            MainThreadDispatcher.schedule(new Runnable() {

                @Override
                public void run() {
                    ScriptNetDispatcher.dispatchIncoming(msg, ctx);
                }
            });
            return null;
        }
    }
}
