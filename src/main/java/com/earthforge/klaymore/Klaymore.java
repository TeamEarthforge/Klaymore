package com.earthforge.klaymore;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;

import com.earthforge.klaymore.script.PersistenceStorage;

@Mod(modid = Klaymore.MODID, version = Tags.VERSION, name = "MyMod", acceptedMinecraftVersions = "[1.7.10]")
public class Klaymore {

    public static final String MODID = "klaymore";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "com.earthforge.klaymore.ClientProxy", serverSide = "com.earthforge.klaymore.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
        LOG.info("[Klaymore] Loading persisted script bindings...");
        try {
            PersistenceStorage.loadAll();
            LOG.info("[Klaymore] Persisted bindings loaded successfully.");
        } catch (Exception e) {
            LOG.error("[Klaymore] Failed to load persisted bindings: " + e.getMessage(), e);
        }
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        LOG.info("[Klaymore] Saving script bindings to disk...");
        try {
            PersistenceStorage.saveAll();
            LOG.info("[Klaymore] Script bindings saved successfully.");
        } catch (Exception e) {
            LOG.error("[Klaymore] Failed to save script bindings: " + e.getMessage(), e);
        }
    }
}
