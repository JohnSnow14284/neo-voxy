package me.cortex.voxy.commonImpl.compat.create;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

//Dedicated-server config (voxy-server.toml, a Type.SERVER config stored per-world under
//serverconfig/) giving the admin a uniform ceiling for distant-train pose streaming that applies to
//every player. This is the only distant-train control a dedicated server has - there is no per-client
//preference sync - and on the integrated server it further caps the host client's own preference.
//Values are pushed into DistantTrainConfig (read by the sampler) whenever the config loads or reloads.
//Registered only when Create is present, so a Create-free server never grows the file or the listeners.
public final class CreateServerConfig {
    private CreateServerConfig() {}

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue DISTANT_TRAINS_ENABLED = BUILDER
            .comment("Master switch for streaming distant train poses to clients. Off = no distant",
                     "trains for anyone on this server (zero train bandwidth).")
            .define("distantTrainsEnabled", true);

    public static final ModConfigSpec.BooleanValue LIMIT_TRAIN_STREAM_DISTANCE = BUILDER
            .comment("Apply a server-wide upper limit to every client's distant train range.",
                     "Disabled by default so clients can follow their own LOD distance. Enable this",
                     "before setting maxTrainStreamChunks when bandwidth needs a uniform cap.")
            .define("limitTrainStreamDistance", false);

    public static final ModConfigSpec.IntValue MAX_TRAIN_STREAM_CHUNKS = BUILDER
            .comment("Uniform ceiling, in chunks, for how far the server streams train poses to any",
                     "client. Lower cuts bandwidth for everyone; a client asking for less still gets",
                     "less. 0 means no additional server cap: each client follows its own configured",
                     "LOD/train distance. Bandwidth scales with the streamed area, so set a positive",
                     "value when the server needs a uniform upper limit.")
            .defineInRange("maxTrainStreamChunks", 0, 0, 2048);

    public static final ModConfigSpec.IntValue SAMPLE_INTERVAL_TICKS = BUILDER
            .comment("Ticks between train pose samples (server-wide). Higher = proportionally less",
                     "bandwidth but choppier distant motion; the client interpolates and adapts to the",
                     "rate automatically. 5 = default (4 samples/second).")
            .defineInRange("sampleIntervalTicks", 5, 1, 40);

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static void register(ModContainer container, IEventBus modEventBus) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC, "voxy-server.toml");
        modEventBus.addListener(CreateServerConfig::onLoad);
        modEventBus.addListener(CreateServerConfig::onReload);
    }

    private static void onLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            push();
        }
    }

    private static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            push();
        }
    }

    private static void push() {
        DistantTrainConfig.updateServerConfig(
                DISTANT_TRAINS_ENABLED.get(),
                LIMIT_TRAIN_STREAM_DISTANCE.get()
                        ? MAX_TRAIN_STREAM_CHUNKS.get() * 16.0
                        : DistantTrainConfig.HARD_MAX,
                SAMPLE_INTERVAL_TICKS.get());
    }
}
