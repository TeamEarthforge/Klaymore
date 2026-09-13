package com.earthforge.klaymore.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.FolderResourcePack;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.common.MinecraftForge;

import com.earthforge.klaymore.Klaymore;
import com.earthforge.klaymore.MinecraftDirectory;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
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
 * <b>关键时序修复</b>：方块在 preInit 阶段注册，TextureMap 的 stitch 发生在 init 阶段。
 * 单纯依赖 {@link IResourceManagerReloadListener} 不够，因为 {@code reloadResources} 内部
 * 先 {@code clearResources} 再 {@code notifyReloadListeners}，而 stitch 发生在两者之间，
 * 导致脚本方块的纹理在 stitch 时找不到。
 * 因此额外监听 {@link TextureStitchEvent.Pre}，在每次 stitch 加载纹理之前确保资源包已注入。
 * </p>
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
        injectAssetPack(resourceManager);
    }

    /**
     * 在每次纹理 stitch 之前确保 Klaymore 资源包已注入。
     * 这是为了覆盖 {@code reloadResources} → {@code clearResources} → stitch →
     * {@code notifyReloadListeners} 这个时序窗口，保证 stitch 时能找到脚本方块的纹理。
     */
    @SubscribeEvent
    public void onTextureStitchPre(TextureStitchEvent.Pre event) {
        try {
            IResourceManager manager = Minecraft.getMinecraft().getResourceManager();
            injectAssetPack(manager);
        } catch (Throwable t) {
            Klaymore.LOG.error("[Klaymore] Failed to inject asset pack before stitch: " + t.getMessage(), t);
        }
    }

    /**
     * 把 Klaymore 资源包注入到资源管理器。幂等操作，重复调用不会产生副作用
     * （{@code reloadResourcePack} 会把 pack 追加到 FallbackResourceManager 列表末尾，
     * 但 FallbackResourceManager 从后往前查找，所以后注入的优先级更高，重复注入无害）。
     */
    private static void injectAssetPack(IResourceManager resourceManager) {
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
                KlaymoreResourceListener listener = new KlaymoreResourceListener();
                ((IReloadableResourceManager) manager).registerReloadListener(listener);
                // 同时注册到 Forge 事件总线，监听 TextureStitchEvent.Pre
                MinecraftForge.EVENT_BUS.register(listener);
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
