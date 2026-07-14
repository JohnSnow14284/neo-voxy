package me.cortex.voxy.client.config;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.compat.far.FarEntityClient;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

@EventBusSubscriber(modid = "voxy", bus = EventBusSubscriber.Bus.MOD)
public final class VoxyNeoForgeConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Enable Voxy LOD rendering system")
            .define("enabled", true);

    private static final ModConfigSpec.BooleanValue ENABLE_RENDERING = BUILDER
            .comment("Enable LOD terrain rendering (can be disabled while keeping data ingestion)")
            .define("enableRendering", true);

    private static final ModConfigSpec.BooleanValue INGEST_ENABLED = BUILDER
            .comment("Enable automatic chunk data ingestion for LOD generation")
            .define("ingestEnabled", true);

    private static final ModConfigSpec.IntValue SECTION_RENDER_DISTANCE = BUILDER
            .comment("LOD section render distance (multiplied by 32 for actual chunk distance)",
                     "Example: 16 = 512 chunks render distance")
            .defineInRange("sectionRenderDistance", 16, 2, 64);

    private static final ModConfigSpec.IntValue SERVICE_THREADS = BUILDER
            .comment("Number of background threads for LOD processing",
                     "Default is based on CPU core count.")
            .defineInRange("serviceThreads", Math.max((int) (CpuLayout.getCoreCount() / 1.5), 1), 1, CpuLayout.getCoreCount());

    private static final ModConfigSpec.DoubleValue SUB_DIVISION_SIZE = BUILDER
            .comment("Subdivision size for LOD rendering (28-256)",
                     "Lower = more detailed LODs but more GPU load")
            .defineInRange("subDivisionSize", 63.0, 28.0, 256.0);

    private static final ModConfigSpec.BooleanValue USE_ENVIRONMENTAL_FOG = BUILDER
            .comment("Apply environmental fog to LOD terrain")
            .define("useEnvironmentalFog", true);

    private static final ModConfigSpec.BooleanValue DONT_USE_SODIUM_BUILDER_THREADS = BUILDER
            .comment("Don't share threads with Sodium's chunk builder")
            .define("dontUseSodiumBuilderThreads", false);

    private static final ModConfigSpec.IntValue LOD_BOUNDARY_BUFFER = BUILDER
            .comment("LOD boundary overlap in blocks (like DH's overdraw prevention)",
                     "Controls how much LODs overlap with vanilla chunk edges.",
                     "Higher values = more overlap = smoother transitions but slight overdraw.",
                     "0 = exact match (may have gaps), 1 = minimal overlap, 2-4 = smoother for fast flight")
            .defineInRange("lodBoundaryBuffer", 1, 0, 4);

    private static final ModConfigSpec.IntValue EARTH_CURVE_RATIO = BUILDER
            .comment("World curvature effect - simulates standing on a spherical planet",
                     "0 = disabled (flat world)",
                     "1 = real Earth curvature (6371km radius)",
                     "Higher values = more extreme curvature (smaller planet effect)",
                     "Valid range: 0 (off), or 50-5000. Values 1-49 are auto-corrected to 50.",
                     "Inspired by Distant Horizons' earth curvature feature")
            .defineInRange("earthCurveRatio", 0, 0, 5000);

    private static final ModConfigSpec.BooleanValue ENABLE_EXTENDED_REQUEST_DISTANCE = BUILDER
            .comment("Enable FakeSight-style extended chunk requests",
                     "When enabled, Voxy reports a larger render/request distance so the server sends more chunks for LOD ingestion.")
            .define("enableExtendedRequestDistance", true);

    private static final ModConfigSpec.IntValue REQUEST_DISTANCE = BUILDER
            .comment("FakeSight request distance in chunks",
                     "This is the chunk distance reported to the server/integrated server.",
                     "Singleplayer expands gradually after movement stops.",
                     "Large values increase server/client load. Maximum: 127.")
            .defineInRange("requestDistance", 48, VoxyConfig.MIN_REQUEST_DISTANCE, VoxyConfig.MAX_REQUEST_DISTANCE);

    private static final ModConfigSpec.BooleanValue ENABLE_FAR_PLAYER_RENDERING = BUILDER
            .comment("Render far players with lightweight server snapshots.",
                     "Multiplayer requires Voxy on the server; standalone SeeU takes precedence when installed.")
            .define("enableFarPlayerRendering", true);

    private static final ModConfigSpec.BooleanValue RENDER_FAR_PLAYER_NAMES = BUILDER
            .comment("Render name tags above far-player proxies.")
            .define("renderFarPlayerNames", true);

    private static final ModConfigSpec.IntValue FAR_PLAYER_ANIMATION_DISTANCE = BUILDER
            .comment("Maximum distance in blocks for far-player walk animation.",
                     "Set to 0 to keep far proxies static and reduce CPU cost.")
            .defineInRange("farPlayerAnimationDistance", 1024, 0, 32768);

    private static final ModConfigSpec.BooleanValue SHARE_FAR_PLAYER_POSITION = BUILDER
            .comment("Allow other Voxy clients on the same server to receive your far-player snapshot.")
            .define("shareFarPlayerPosition", true);

    private static final ModConfigSpec.BooleanValue RENDER_STATISTICS = BUILDER
            .comment("Show render statistics in F3 debug screen",
                     "Displays LOD traversal counts, visible sections, and quad counts")
            .define("renderStatistics", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private VoxyNeoForgeConfig() {
    }

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC, "voxy-client.toml");
    }

    private static void syncToVoxyConfig() {
        VoxyConfig.CONFIG.enabled = ENABLED.get();
        VoxyConfig.CONFIG.enableRendering = ENABLE_RENDERING.get();
        VoxyConfig.CONFIG.ingestEnabled = INGEST_ENABLED.get();
        VoxyConfig.CONFIG.sectionRenderDistance = SECTION_RENDER_DISTANCE.get();
        VoxyConfig.CONFIG.serviceThreads = SERVICE_THREADS.get();
        VoxyConfig.CONFIG.subDivisionSize = SUB_DIVISION_SIZE.get().floatValue();
        VoxyConfig.CONFIG.useEnvironmentalFog = USE_ENVIRONMENTAL_FOG.get();
        VoxyConfig.CONFIG.dontUseSodiumBuilderThreads = DONT_USE_SODIUM_BUILDER_THREADS.get();
        VoxyConfig.CONFIG.lodBoundaryBuffer = LOD_BOUNDARY_BUFFER.get();
        VoxyConfig.CONFIG.earthCurveRatio = EARTH_CURVE_RATIO.get();
        VoxyConfig.CONFIG.enableExtendedRequestDistance = ENABLE_EXTENDED_REQUEST_DISTANCE.get();
        VoxyConfig.CONFIG.requestDistance = REQUEST_DISTANCE.get();
        VoxyConfig.CONFIG.enableFarPlayerRendering = ENABLE_FAR_PLAYER_RENDERING.get();
        VoxyConfig.CONFIG.renderFarPlayerNames = RENDER_FAR_PLAYER_NAMES.get();
        VoxyConfig.CONFIG.farPlayerAnimationDistance = FAR_PLAYER_ANIMATION_DISTANCE.get();
        VoxyConfig.CONFIG.shareFarPlayerPosition = SHARE_FAR_PLAYER_POSITION.get();
        VoxyConfig.CONFIG.sanitize();

        RenderStatistics.enabled = RENDER_STATISTICS.get();

        VoxyConfig.CONFIG.save();
    }

    private static void syncFromVoxyConfig() {
        VoxyConfig.CONFIG.sanitize();
        ENABLED.set(VoxyConfig.CONFIG.enabled);
        ENABLE_RENDERING.set(VoxyConfig.CONFIG.enableRendering);
        INGEST_ENABLED.set(VoxyConfig.CONFIG.ingestEnabled);
        SECTION_RENDER_DISTANCE.set((int) VoxyConfig.CONFIG.sectionRenderDistance);
        SERVICE_THREADS.set(VoxyConfig.CONFIG.serviceThreads);
        SUB_DIVISION_SIZE.set((double) VoxyConfig.CONFIG.subDivisionSize);
        USE_ENVIRONMENTAL_FOG.set(VoxyConfig.CONFIG.useEnvironmentalFog);
        DONT_USE_SODIUM_BUILDER_THREADS.set(VoxyConfig.CONFIG.dontUseSodiumBuilderThreads);
        LOD_BOUNDARY_BUFFER.set(VoxyConfig.CONFIG.lodBoundaryBuffer);
        EARTH_CURVE_RATIO.set(VoxyConfig.CONFIG.earthCurveRatio);
        ENABLE_EXTENDED_REQUEST_DISTANCE.set(VoxyConfig.CONFIG.enableExtendedRequestDistance);
        REQUEST_DISTANCE.set(VoxyConfig.CONFIG.requestDistance);
        ENABLE_FAR_PLAYER_RENDERING.set(VoxyConfig.CONFIG.enableFarPlayerRendering);
        RENDER_FAR_PLAYER_NAMES.set(VoxyConfig.CONFIG.renderFarPlayerNames);
        FAR_PLAYER_ANIMATION_DISTANCE.set(VoxyConfig.CONFIG.farPlayerAnimationDistance);
        SHARE_FAR_PLAYER_POSITION.set(VoxyConfig.CONFIG.shareFarPlayerPosition);

        RenderStatistics.enabled = RENDER_STATISTICS.get();
    }

    @SubscribeEvent
    public static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            syncFromVoxyConfig();
        }
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            syncToVoxyConfig();
            FarEntityClient.sendHello();
        }
    }

}
