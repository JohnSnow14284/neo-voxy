package me.cortex.voxy;

import me.cortex.voxy.client.config.VoxyNeoForgeConfig;
import me.cortex.voxy.compat.far.FarEntityClient;
import me.cortex.voxy.compat.far.FarEntityProtocol;
import me.cortex.voxy.compat.far.FarEntityService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Main mod class for Voxy on NeoForge.
 *
 * Handles config registration and config screen setup.
 * Actual initialization happens via mixins (MixinRenderSystem).
 */
@Mod("voxy")
public class Voxy {
    private final FarEntityService farEntityService = new FarEntityService();

    public Voxy(IEventBus modEventBus, ModContainer container) {
        modEventBus.addListener(this::registerFarEntityPayloads);
        NeoForge.EVENT_BUS.addListener(this.farEntityService::onServerTick);
        NeoForge.EVENT_BUS.addListener(this.farEntityService::onPlayerLoggedOut);

        if (FMLLoader.getDist() == Dist.CLIENT) {
            VoxyNeoForgeConfig.register(container);
            container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        }
    }

    private void registerFarEntityPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("voxy")
                .versioned(Integer.toString(FarEntityProtocol.VERSION))
                .optional();

        registrar.playToServer(FarEntityProtocol.HelloPayload.TYPE, FarEntityProtocol.HelloPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        this.farEntityService.handleHello((ServerPlayer) context.player(), payload.hello())));

        if (FMLLoader.getDist() == Dist.CLIENT) {
            registrar.playToClient(FarEntityProtocol.PlayersPayload.TYPE, FarEntityProtocol.PlayersPayload.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(() -> FarEntityClient.handle(payload.batch())));
        } else {
            registrar.playToClient(FarEntityProtocol.PlayersPayload.TYPE, FarEntityProtocol.PlayersPayload.STREAM_CODEC,
                    (payload, context) -> { });
        }
    }
}
