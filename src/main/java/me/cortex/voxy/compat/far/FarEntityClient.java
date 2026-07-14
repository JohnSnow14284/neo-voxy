package me.cortex.voxy.compat.far;

import me.cortex.voxy.client.config.VoxyConfig;
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

    private FarEntityClient() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        TRACKER.clear();
        RENDERER.clear();
        sendHello();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        RENDERER.clear();
        TRACKER.clear();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        RENDERER.render(event);
    }

    public static void handle(FarEntityProtocol.PlayerBatch batch) {
        if (!isEnabled()) {
            RENDERER.clear();
            TRACKER.clear();
            return;
        }
        TRACKER.apply(batch);
    }

    static boolean isEnabled() {
        return VoxyConfig.CONFIG.enableFarPlayerRendering
                && VoxyConfig.CONFIG.isRenderingEnabled()
                && !ModList.get().isLoaded("seeu");
    }

    public static void sendHello() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        boolean createEnabled = VoxyConfig.CONFIG.enableCreateFarEntityRendering
                && VoxyConfig.CONFIG.isRenderingEnabled();
        PacketDistributor.sendToServer(new FarEntityProtocol.HelloPayload(new FarEntityProtocol.Hello(
                FarEntityProtocol.VERSION,
                isEnabled(),
                VoxyConfig.CONFIG.getFarEntityRenderDistanceBlocks(),
                VoxyConfig.CONFIG.shareFarPlayerPosition,
                createEnabled,
                VoxyConfig.CONFIG.getCreateContraptionRenderDistance(),
                VoxyConfig.CONFIG.getCreateTrainRenderDistance()
        )));
    }
}
