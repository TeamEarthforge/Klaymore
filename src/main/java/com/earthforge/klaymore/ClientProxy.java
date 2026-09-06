package com.earthforge.klaymore;

import com.earthforge.klaymore.script.GlobalRoot;

import cpw.mods.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {
    // Override CommonProxy methods here, if you want a different behaviour on the client (e.g. registering renders).
    // Don't forget to call the super methods as well.

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);

        // 客户端根脚本尽早挂载（init 阶段，主菜单出现之前）。
        // 这样客户端脚本可以处理"不在服务器时"的逻辑（主菜单 UI、资源初始化等）。
        try {
            GlobalRoot.mountClientIfPresent();
        } catch (Throwable t) {
            System.err.println("[Klaymore ClientProxy] Failed to mount client Root.kt: " + t.getMessage());
        }
    }
}
