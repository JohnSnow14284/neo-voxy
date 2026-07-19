package me.cortex.voxy.commonImpl.compat.sable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.cortex.voxy.common.Logger;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SableContraptionRenderDistance {
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("voxy-config.json");
    private static final Path SABLE_COMMON_CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("sable-common.toml");
    private static final int CONFIG_REFRESH_TICKS = 20;
    private static final int CHUNKS_PER_SECTION_RENDER_DISTANCE = 32;
    private static final int BLOCKS_PER_CHUNK = 16;
    private static final double DEFAULT_SECTION_RENDER_DISTANCE = 16.0;
    private static final int DEFAULT_PERCENT = 50;
    private static final double DEDICATED_SERVER_FALLBACK_BLOCKS = 2048.0;

    private static final ConfigSnapshot DISABLED_CONFIG = new ConfigSnapshot(false, 0.0, DEFAULT_PERCENT, Long.MIN_VALUE);

    private static ConfigSnapshot cachedConfig = DISABLED_CONFIG;
    private static volatile ConfigSnapshot runtimeClientConfig;
    private static long nextConfigRefreshTick;
    private static double cachedDedicatedServerRangeBlocks = DEDICATED_SERVER_FALLBACK_BLOCKS;
    private static long cachedDedicatedServerConfigLastModified = Long.MIN_VALUE;
    private static long nextDedicatedServerConfigRefreshTick;

    private SableContraptionRenderDistance() {
    }

    public static double getRangeBlocks(ServerLevel level) {
        if (level.getServer().isDedicatedServer()) {
            return getDedicatedServerRangeBlocks(level.getGameTime());
        }

        ConfigSnapshot config = runtimeClientConfig;
        if (config == null) {
            config = getConfig(level.getGameTime());
        }
        if (!config.enabled()) {
            return 0.0;
        }

        int vanillaRenderDistanceChunks = getVanillaRenderDistanceChunks(level);
        int contraptionDistanceChunks = extendVanillaRenderDistanceChunks(
                vanillaRenderDistanceChunks,
                config.sectionRenderDistance(),
                config.simulatedContraptionRenderDistancePercent()
        );
        return contraptionDistanceChunks * BLOCKS_PER_CHUNK;
    }

    public static int extendVanillaRenderDistanceChunks(int vanillaRenderDistanceChunks, double sectionRenderDistance, int simulatedContraptionRenderDistancePercent) {
        int vanillaDistanceChunks = Math.max(0, vanillaRenderDistanceChunks);
        int percent = Math.max(0, Math.min(100, simulatedContraptionRenderDistancePercent));
        if (percent == 0) {
            return vanillaDistanceChunks;
        }

        int voxyRenderDistanceChunks = (int) Math.ceil(sectionRenderDistance * CHUNKS_PER_SECTION_RENDER_DISTANCE);
        if (sectionRenderDistance <= 0.0) {
            return vanillaDistanceChunks;
        }

        return Math.max(0, (int) Math.ceil(vanillaDistanceChunks + ((voxyRenderDistanceChunks - vanillaDistanceChunks) * (percent / 100.0D))));
    }

    private static int getVanillaRenderDistanceChunks(ServerLevel level) {
        return Math.max(0, level.getServer().getPlayerList().getViewDistance());
    }

    public static void updateClientConfig(boolean enabled, double sectionRenderDistance, int simulatedContraptionRenderDistancePercent) {
        if (!enabled || sectionRenderDistance <= 0.0) {
            runtimeClientConfig = new ConfigSnapshot(false, 0.0, simulatedContraptionRenderDistancePercent, Long.MAX_VALUE);
            return;
        }

        runtimeClientConfig = new ConfigSnapshot(true, sectionRenderDistance, simulatedContraptionRenderDistancePercent, Long.MAX_VALUE);
    }

    private static double getDedicatedServerRangeBlocks(long gameTime) {
        if (gameTime < nextDedicatedServerConfigRefreshTick) {
            return cachedDedicatedServerRangeBlocks;
        }
        nextDedicatedServerConfigRefreshTick = gameTime + CONFIG_REFRESH_TICKS;

        long lastModified;
        try {
            lastModified = Files.exists(SABLE_COMMON_CONFIG_PATH) ? Files.getLastModifiedTime(SABLE_COMMON_CONFIG_PATH).toMillis() : Long.MIN_VALUE;
        } catch (IOException e) {
            return DEDICATED_SERVER_FALLBACK_BLOCKS;
        }

        if (cachedDedicatedServerConfigLastModified == lastModified) {
            return cachedDedicatedServerRangeBlocks;
        }

        cachedDedicatedServerConfigLastModified = lastModified;
        cachedDedicatedServerRangeBlocks = loadDedicatedServerRangeBlocks();
        return cachedDedicatedServerRangeBlocks;
    }

    private static double loadDedicatedServerRangeBlocks() {
        if (!Files.exists(SABLE_COMMON_CONFIG_PATH)) {
            return DEDICATED_SERVER_FALLBACK_BLOCKS;
        }

        try (BufferedReader reader = Files.newBufferedReader(SABLE_COMMON_CONFIG_PATH)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int commentStart = line.indexOf('#');
                if (commentStart >= 0) {
                    line = line.substring(0, commentStart);
                }

                line = line.trim();
                if (!line.startsWith("sub_level_tracking_range")) {
                    continue;
                }

                int assignment = line.indexOf('=');
                if (assignment < 0) {
                    continue;
                }

                return Math.max(0.0, Double.parseDouble(line.substring(assignment + 1).trim()));
            }
        } catch (IOException | RuntimeException e) {
            Logger.error("Failed to load Sable sub_level_tracking_range for Voxy Sable LOD compatibility", e);
        }

        return DEDICATED_SERVER_FALLBACK_BLOCKS;
    }

    private static ConfigSnapshot getConfig(long gameTime) {
        if (gameTime < nextConfigRefreshTick) {
            return cachedConfig;
        }
        nextConfigRefreshTick = gameTime + CONFIG_REFRESH_TICKS;

        long lastModified;
        try {
            lastModified = Files.exists(CONFIG_PATH) ? Files.getLastModifiedTime(CONFIG_PATH).toMillis() : Long.MIN_VALUE;
        } catch (IOException e) {
            Logger.error("Failed to stat Voxy config for Sable simulated contraption render distance", e);
            return cachedConfig;
        }

        if (cachedConfig.lastModifiedMillis() == lastModified) {
            return cachedConfig;
        }

        cachedConfig = loadConfig(lastModified);
        return cachedConfig;
    }

    private static ConfigSnapshot loadConfig(long lastModified) {
        if (!Files.exists(CONFIG_PATH)) {
            return new ConfigSnapshot(true, DEFAULT_SECTION_RENDER_DISTANCE, DEFAULT_PERCENT, lastModified);
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            boolean enabled = getBoolean(root, "enabled", true) && getBoolean(root, "enable_rendering", true);
            double sectionRenderDistance = getDouble(root, "section_render_distance", DEFAULT_SECTION_RENDER_DISTANCE);
            int simulatedContraptionPercent = getInt(root, "simulated_contraption_render_distance_percent", DEFAULT_PERCENT);

            if (!enabled || sectionRenderDistance <= 0.0) {
                return new ConfigSnapshot(false, 0.0, simulatedContraptionPercent, lastModified);
            }

            return new ConfigSnapshot(true, sectionRenderDistance, simulatedContraptionPercent, lastModified);
        } catch (Exception e) {
            Logger.error("Failed to load Voxy config for Sable simulated contraption render distance", e);
            return DISABLED_CONFIG;
        }
    }

    private static boolean getBoolean(JsonObject root, String key, boolean fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        return root.get(key).getAsBoolean();
    }

    private static double getDouble(JsonObject root, String key, double fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        return root.get(key).getAsDouble();
    }

    private static int getInt(JsonObject root, String key, int fallback) {
        if (!root.has(key) || root.get(key).isJsonNull()) {
            return fallback;
        }
        return root.get(key).getAsInt();
    }

    private record ConfigSnapshot(
            boolean enabled,
            double sectionRenderDistance,
            int simulatedContraptionRenderDistancePercent,
            long lastModifiedMillis
    ) {
    }
}
