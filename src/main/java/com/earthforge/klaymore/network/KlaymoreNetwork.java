package com.earthforge.klaymore.network;

import com.earthforge.klaymore.Klaymore;
import com.earthforge.klaymore.script.ScriptMessagePacket;
import com.earthforge.klaymore.wand.WandBindPacket;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public final class KlaymoreNetwork {

    public static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel(Klaymore.MODID);

    private static int nextDiscriminator = 0;

    private KlaymoreNetwork() {}

    public static void registerMessages() {
        CHANNEL.registerMessage(WandBindPacket.Handler.class, WandBindPacket.class, nextDiscriminator++, Side.SERVER);
        // 脚本通用通信包：双向（C→S 和 S→C 都走同一个 Handler，内部按 ctx.side 区分）
        CHANNEL.registerMessage(
            ScriptMessagePacket.Handler.class,
            ScriptMessagePacket.class,
            nextDiscriminator++,
            Side.CLIENT);
        CHANNEL.registerMessage(
            ScriptMessagePacket.Handler.class,
            ScriptMessagePacket.class,
            nextDiscriminator++,
            Side.SERVER);
    }
}
