package me.cortex.voxy;

import me.cortex.voxy.client.config.VoxyNeoForgeConfig;
import me.cortex.voxy.compat.far.FarEntityClient;
import me.cortex.voxy.compat.far.FarEntityProtocol;
import me.cortex.voxy.compat.far.FarEntityService;
import me.cortex.voxy.common.Logger;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
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

        // Only register client config on client side
        if (FMLLoader.getDist() == Dist.CLIENT) {
            // Register NeoForge config
            VoxyNeoForgeConfig.register(container);

            // Register the built-in NeoForge config screen
            container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

            // Register Sodium Options API integration if available
            // This adds Voxy settings to Sodium's Video Settings menu
            // Uses reflection to avoid hard dependency - graceful fallback if not present
            tryRegisterSodiumOptionsIntegration();
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

    /**
     * Attempts to register the Sodium Options API integration.
     * Uses reflection to avoid class loading errors when the API is not present.
     * Falls back gracefully to NeoForge config screen if unavailable.
     */
    private static void tryRegisterSodiumOptionsIntegration() {
        if (!ModList.get().isLoaded("sodiumoptionsapi")) {
            Logger.info("SodiumOptionsAPI not found - Voxy settings available via Mods menu");
            return;
        }

        try {
            // Load and invoke the integration class only when we know the API is present
            // This prevents NoClassDefFoundError when SodiumOptionsAPI is not installed
            Class<?> sodiumOptionsClass = Class.forName("me.cortex.voxy.client.config.VoxySodiumOptions");
            sodiumOptionsClass.getMethod("register").invoke(null);
            Logger.info("Registered Voxy settings in Sodium Video Settings menu");
        } catch (Throwable e) {
            Logger.warn("Failed to register Sodium Options integration: " + e.getMessage());
            Logger.info("Voxy settings available via Mods menu instead");
        }
    }
}
