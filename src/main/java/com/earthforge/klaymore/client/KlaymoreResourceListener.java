package com.earthforge.klaymore.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.FolderResourcePack;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.resources.SimpleReloadableResourceManager;

import com.earthforge.klaymore.Klaymore;
import com.earthforge.klaymore.MinecraftDirectory;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 把 <mcRoot>/klaymore/assets/ 当作一个 MC 资源包注入到客户端资源管理器。
 *
 * <p>
 * 工作原理（参考 CustomNPCs 的 CustomNpcResourceListener）：
 * </p>
 * <ol>
 * <li>实现 {@link IResourceManagerReloadListener}，由 MC 在每次资源重载时回调</li>
 * <li>在 {@link #onResourceManagerReload} 中创建一个指向 Klaymore 根目录的
 * {@link FolderResourcePack}，调用 {@link SimpleReloadableResourceManager#reloadResourcePack}
 * 注入到资源管理器</li>
 * <li>{@link net.minecraft.client.resources.FallbackResourceManager} 从 pack 列表末尾向前查找资源，
 * 因此后注入的 Klaymore pack 优先级高于原版资源</li>
 * <li>F3+T 触发 refreshResources → reloadResources（内部先 clearResources 再逐个 reloadResourcePack，
 * 最后 notifyReloadListeners），我们的 listener 会重新注入 pack，实现热重载</li>
 * </ol>
 *
 * <p>
 * 目录结构：
 * </p>
 * 
 * <pre>
 *   klaymore/
 *     assets/
 *       &lt;namespace&gt;/
 *         textures/...
 *         lang/...
 *         models/...
 * </pre>
 */
@SideOnly(Side.CLIENT)
public class KlaymoreResourceListener implements IResourceManagerReloadListener {

    @Override
    public void onResourceManagerReload(IResourceManager resourceManager) {
        if (!(resourceManager instanceof SimpleReloadableResourceManager)) {
            return;
        }
        SimpleReloadableResourceManager simpleManager = (SimpleReloadableResourceManager) resourceManager;

        try {
            FolderResourcePack pack = new FolderResourcePack(MinecraftDirectory.getKlaymoreRoot());
            simpleManager.reloadResourcePack(pack);
            Klaymore.LOG.info(
                "[Klaymore] Injected asset pack from " + MinecraftDirectory.getKlaymoreRoot()
                    .getAbsolutePath() + " (domains: " + pack.getResourceDomains() + ")");
        } catch (Throwable t) {
            Klaymore.LOG.error("[Klaymore] Failed to inject Klaymore asset pack: " + t.getMessage(), t);
        }
    }

    /**
     * 向 Minecraft 资源管理器注册此监听器。
     * 应在客户端 init 阶段调用，此时 {@code Minecraft.getMinecraft()} 已就绪。
     */
    public static void register() {
        try {
            IResourceManager manager = Minecraft.getMinecraft()
                .getResourceManager();
            if (manager instanceof IReloadableResourceManager) {
                ((IReloadableResourceManager) manager).registerReloadListener(new KlaymoreResourceListener());
                Klaymore.LOG.info("[Klaymore] Resource reload listener registered");
            } else {
                Klaymore.LOG.warn(
                    "[Klaymore] Cannot register resource listener: resource manager is " + (manager == null ? "null"
                        : manager.getClass()
                            .getName()));
            }
        } catch (Throwable t) {
            Klaymore.LOG.error("[Klaymore] Failed to register resource reload listener: " + t.getMessage(), t);
        }
    }
}
