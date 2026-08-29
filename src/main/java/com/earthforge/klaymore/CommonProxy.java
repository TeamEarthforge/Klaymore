package com.earthforge.klaymore;

import com.earthforge.klaymore.command.KlaymoreCommand;
import com.earthforge.klaymore.item.KlaymoreItems;
import com.earthforge.klaymore.network.KlaymoreNetwork;
import com.earthforge.klaymore.wand.WandGuiHandler;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.network.NetworkRegistry;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());
        Klaymore.LOG.info("I am MyMod at version " + Tags.VERSION);

        try {
            KlaymoreItems.registerAll();
            Klaymore.LOG.info("[Klaymore] Items registered (Klaymore Wand, etc.)");
        } catch (Throwable t) {
            Klaymore.LOG.error("[Klaymore] Failed to register items: " + t.getMessage(), t);
        }

        try {
            KlaymoreNetwork.registerMessages();
            Klaymore.LOG.info("[Klaymore] Network channel & packets registered");
        } catch (Throwable t) {
            Klaymore.LOG.error("[Klaymore] Failed to register network messages: " + t.getMessage(), t);
        }
    }

    public void init(FMLInitializationEvent event) {
        try {
            NetworkRegistry.INSTANCE.registerGuiHandler(Klaymore.instance, new WandGuiHandler());
            Klaymore.LOG.info("[Klaymore] Wand GUI handler registered");
        } catch (Throwable t) {
            Klaymore.LOG.error("[Klaymore] Failed to register Wand GUI handler: " + t.getMessage(), t);
        }
    }

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new KlaymoreCommand());
    }
}
