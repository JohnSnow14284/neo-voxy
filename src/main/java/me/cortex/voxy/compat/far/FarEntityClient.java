package me.cortex.voxy.compat.far;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public final class FarEntityClient {
    private static final FarPlayerTracker TRACKER = new FarPlayerTracker();
    private static final FarEntityRenderer RENDERER = new FarEntityRenderer(TRACKER);
    private static final FarContraptionClient CREATE_CONTRAPTIONS = createContraptionClient();

    private FarEntityClient() {
    }

    private static FarContraptionClient createContraptionClient() {
        if (!ModList.get().isLoaded("create")) return null;
        try {
            return Class.forName("me.cortex.voxy.compat.far.create.CreateFarContraptionClient")
                    .asSubclass(FarContraptionClient.class)
                    .getConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            Logger.error("Could not initialize Create far-contraption rendering", e);
            return null;
        }
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        TRACKER.clear();
        RENDERER.clear();
        if (CREATE_CONTRAPTIONS != null) CREATE_CONTRAPTIONS.clear();
        sendHello();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        RENDERER.clear();
        TRACKER.clear();
        if (CREATE_CONTRAPTIONS != null) CREATE_CONTRAPTIONS.clear();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        RENDERER.render(event);
        if (CREATE_CONTRAPTIONS != null) CREATE_CONTRAPTIONS.render(event);
    }

    public static void handle(FarEntityProtocol.PlayerBatch batch) {
        if (!isEnabled()) {
            RENDERER.clear();
            TRACKER.clear();
            return;
        }
        TRACKER.apply(batch);
    }

    public static void handle(FarEntityProtocol.ContraptionBatch batch) {
        if (CREATE_CONTRAPTIONS == null || !isCreateContraptionRenderingEnabled()) {
            if (CREATE_CONTRAPTIONS != null) CREATE_CONTRAPTIONS.clear();
            return;
        }
        CREATE_CONTRAPTIONS.apply(batch);
    }

    static boolean isEnabled() {
        return VoxyConfig.CONFIG.enableFarPlayerRendering
                && VoxyConfig.CONFIG.isRenderingEnabled()
                && !ModList.get().isLoaded("seeu");
    }

    static boolean isCreateContraptionRenderingEnabled() {
        return VoxyConfig.CONFIG.enableFarCreateContraptionRendering
                && VoxyConfig.CONFIG.isRenderingEnabled()
                && CREATE_CONTRAPTIONS != null;
    }

    public static void sendHello() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        PacketDistributor.sendToServer(new FarEntityProtocol.HelloPayload(new FarEntityProtocol.Hello(
                FarEntityProtocol.VERSION,
                isEnabled(),
                VoxyConfig.CONFIG.getFarEntityRenderDistanceBlocks(),
                VoxyConfig.CONFIG.shareFarPlayerPosition,
                isCreateContraptionRenderingEnabled(),
                VoxyConfig.CONFIG.getFarCreateContraptionDistanceBlocks()
        )));
    }
}
