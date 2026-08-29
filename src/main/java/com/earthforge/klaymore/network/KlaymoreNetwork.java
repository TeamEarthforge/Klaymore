package com.earthforge.klaymore.network;

import com.earthforge.klaymore.Klaymore;
import com.earthforge.klaymore.wand.WandBindPacket;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public final class KlaymoreNetwork {

    public static final SimpleNetworkWrapper CHANNEL =
        NetworkRegistry.INSTANCE.newSimpleChannel(Klaymore.MODID);

    private static int nextDiscriminator = 0;

    private KlaymoreNetwork() {}

    public static void registerMessages() {
        CHANNEL.registerMessage(WandBindPacket.Handler.class, WandBindPacket.class,
            nextDiscriminator++, Side.SERVER);
    }
}
