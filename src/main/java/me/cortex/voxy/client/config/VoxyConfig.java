package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.fml.loading.FMLPaths;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public class VoxyConfig {
    public enum LeafLodMode {
        FAST,
        BALANCED,
        QUALITY
    }

    public static final int MIN_REQUEST_DISTANCE = 8;
    // ClientInformation carries view distance in one signed byte. Keeping the
    // singleplayer extension at 127 avoids wraparound while removing the old 48 cap.
    public static final int MAX_REQUEST_DISTANCE = 127;
    public static final int MIN_CREATE_RENDER_DISTANCE = 8;
    public static final int MAX_CREATE_RENDER_DISTANCE = 127;
    public static final int MAX_CLOUD_DISTANCE = 128;
    public static final float MIN_SUBDIVISION_SIZE = 28.0f;
    public static final float MAX_SUBDIVISION_SIZE = 256.0f;

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.TRANSIENT)
            .create();

    public static final VoxyConfig CONFIG = loadOrCreate();

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public float sectionRenderDistance = 16;
    public int serviceThreads = Math.max((int) (CpuLayout.getCoreCount() / 1.5), 1);
    public float subDivisionSize = 63;
    public int skyFogDistance = 96;
    public float fogIntensity = 1.0f;
    public float fogDensity = 0.0f;
    public boolean adaptCloudDistance = true;
    public int cloudDistance = 0;
    public boolean dontUseSodiumBuilderThreads = false;
    public int renderPressure = 2;
    public int lodBoundaryBuffer = 1;
    public int earthCurveRatio = 0;
    public boolean enableExtendedRequestDistance = true;
    public int requestDistance = 48;
    public String ssaoMode;
    public boolean useEnvironmentalFog = true;
    public String leafLodMode = "balanced";
    public boolean enableFarPlayerRendering = true;
    public boolean renderFarPlayerNames = true;
    public int farPlayerAnimationDistance = 1024;
    public boolean shareFarPlayerPosition = true;
    public boolean enableCreateFarEntityRendering = true;
    public int createContraptionRenderDistance = 48;
    public int createTrainRenderDistance = 96;

    public int getRequestDistance() {
        return Math.clamp(this.requestDistance, MIN_REQUEST_DISTANCE, MAX_REQUEST_DISTANCE);
    }

    public int getCreateContraptionRenderDistance() {
        return Math.clamp(this.createContraptionRenderDistance,
                MIN_CREATE_RENDER_DISTANCE, MAX_CREATE_RENDER_DISTANCE);
    }

    public int getCreateTrainRenderDistance() {
        return Math.clamp(this.createTrainRenderDistance,
                MIN_CREATE_RENDER_DISTANCE, MAX_CREATE_RENDER_DISTANCE);
    }

    public int getEffectiveRequestDistance() {
        return this.enableCreateFarEntityRendering
                ? Math.max(this.getRequestDistance(), this.getCreateContraptionRenderDistance())
                : this.getRequestDistance();
    }

    public int getFarEntityRenderDistanceBlocks() {
        return Math.clamp(Math.round(this.sectionRenderDistance * 32.0f * 16.0f), 64, 32768);
    }

    public int getRenderPressureLevel() {
        if (this.renderPressure < 0 || this.renderPressure > 4) {
            this.renderPressure = 2;
        }
        return this.renderPressure;
    }

    public LeafLodMode getLeafLodMode() {
        if (this.leafLodMode == null) {
            return LeafLodMode.BALANCED;
        }

        try {
            return LeafLodMode.valueOf(this.leafLodMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return LeafLodMode.BALANCED;
        }
    }

    public void setLeafLodMode(LeafLodMode mode) {
        this.leafLodMode = mode.name().toLowerCase(Locale.ROOT);
    }

    public SSAO.SSAOMode getSSAOMode() {
        if (this.ssaoMode == null) {
            return SSAO.SSAOMode.AUTO;
        }

        try {
            return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SSAO.SSAOMode.AUTO;
        }
    }

    public void setSSAOMode(SSAO.SSAOMode mode) {
        this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
    }

    private static VoxyConfig loadOrCreate() {
        if (!VoxyCommon.isAvailable()) {
            var config = new VoxyConfig();
            config.enabled = false;
            config.enableRendering = false;
            return config;
        }

        Path path = getConfigPath();
        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                VoxyConfig config = GSON.fromJson(reader, VoxyConfig.class);
                if (config != null) {
                    config.sanitize();
                    config.save();
                    return config;
                }
                Logger.error("Failed to load Voxy config; resetting it");
            } catch (IOException | RuntimeException e) {
                Logger.error("Could not load Voxy config; resetting it", e);
                backupInvalidConfig(path);
            }
        }

        Logger.info("Config does not exist; creating a new one");
        var config = new VoxyConfig();
        config.save();
        return config;
    }

    public void sanitize() {
        this.subDivisionSize = Math.clamp(this.subDivisionSize, MIN_SUBDIVISION_SIZE, MAX_SUBDIVISION_SIZE);
        this.requestDistance = Math.clamp(this.requestDistance, MIN_REQUEST_DISTANCE, MAX_REQUEST_DISTANCE);
        this.skyFogDistance = Math.clamp(this.skyFogDistance, 0, 1024);
        this.cloudDistance = Math.clamp(this.cloudDistance, 0, MAX_CLOUD_DISTANCE);
        this.fogIntensity = Math.clamp(this.fogIntensity, 0.0f, 1.0f);
        this.fogDensity = Math.clamp(this.fogDensity, 0.0f, 1.0f);
        this.setLeafLodMode(this.getLeafLodMode());
        this.farPlayerAnimationDistance = Math.clamp(this.farPlayerAnimationDistance, 0, 32768);
        this.createContraptionRenderDistance = this.getCreateContraptionRenderDistance();
        this.createTrainRenderDistance = this.getCreateTrainRenderDistance();
    }

    public void save() {
        if (!VoxyCommon.isAvailable()) {
            Logger.info("Not saving config because Voxy is unavailable");
            return;
        }

        this.sanitize();
        Path path = getConfigPath();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, GSON.toJson(this));
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Logger.error("Failed to write Voxy config", e);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    private static void backupInvalidConfig(Path path) {
        try {
            Path backup = path.resolveSibling(path.getFileName() + ".invalid");
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Logger.error("Failed to back up invalid Voxy config", e);
        }
    }

    private static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get().resolve("voxy-config.json");
    }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
    }
}
