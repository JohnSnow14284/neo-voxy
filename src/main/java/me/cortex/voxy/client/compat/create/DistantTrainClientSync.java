package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainConfig;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.TrainRenderRequestPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

//Event-driven client -> server distance synchronization. This deliberately has no tick handler:
//the value changes only on login or when the user applies configuration changes.
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public final class DistantTrainClientSync {
    private DistantTrainClientSync() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        sendCurrent();
    }

    public static void sendCurrent() {
        //VoxyConfig is also initialized from Iris's very early shader-macro setup, before NeoForge
        //has published ModList. Config persistence may call us there; defer naturally until the
        //LoggingIn event instead of crashing the whole client during renderer initialization.
        ModList modList = ModList.get();
        if (modList == null || !modList.isLoaded("create")) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        VoxyConfig config = VoxyConfig.CONFIG;
        double requested = config.createRenderDistance(config.distantTrainMaxChunks);
        int blocks = (int) Math.clamp(Math.round(requested), 1L, (long) DistantTrainConfig.HARD_MAX);
        PacketDistributor.sendToServer(new TrainRenderRequestPayload(
                config.isRenderingEnabled() && config.distantTrains,
                blocks));
    }
}
