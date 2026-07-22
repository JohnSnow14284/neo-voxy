package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainConfig;
import me.cortex.voxy.commonImpl.compat.sable.SableContraptionRenderDistance;
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
    // ClientInformation serializes the view distance as one signed byte.
    public static final int MAX_REQUEST_DISTANCE = 127;
    // A 127-chunk integrated-server radius covers roughly 65k chunks and can stall both the server
    // and client render thread. Dedicated servers retain their own configured limit.
    public static final int MAX_INTEGRATED_REQUEST_DISTANCE = 32;
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
    // Aero/sable: master switch for extending simulated-contraption rendering out to LOD distances.
    public boolean sableLodRendering = true;
    // Create: render distant trains (server-sampled poses + client-baked carriage meshes).
    public boolean distantTrains = true;
    // Create: render the track network beyond render distance as simplified rail geometry.
    public boolean distantTracks = true;
    // Create: hold a frozen client-side snapshot of contraptions (bearings/pistons/gantries/mounted)
    // the player walked past, drawn statically beyond the render distance.
    public boolean distantContraptions = true;
    // Draw beacon beams past vanilla's own block-entity render range. The beam is rebuilt from the
    // persistent Voxy voxel store, so it remains available when the source chunk is not loaded.
    public boolean distantBeacons = true;
    // Maximum beacon-beam distance in chunks. 0 follows Voxy's LOD radius.
    public int distantBeaconMaxChunks = 192;
    // Create: cull placed kinetic machine moving parts (rotating shafts/gears/machine animations)
    // beyond the render distance so they stop floating over the LOD. Off = Create draws them natively.
    public boolean distantKinetics = true;
    // Create: hide kinetic moving parts that are provably invisible (all open faces covered by opaque
    // blocks; encased blocks only need their two axis ends covered). Pure render savings, active even
    // with voxy rendering off; complements the raycast culler, which cannot catch this case.
    public boolean kineticEnclosedCulling = true;
    // Create distant-integration render caps, in CHUNKS. 0 follows Voxy's LOD radius. Bounded defaults
    // keep tiny distant machinery from holding meshes and draw calls all the way to the terrain horizon.
    public int distantTrainMaxChunks = 96;
    public int distantTrackMaxChunks = 96;
    public int distantContraptionMaxChunks = 64;
    public int distantKineticMaxChunks = 48;
    // GPU working-set budgets. Contraption source data is retained for cheap rebuilds; kinetic captures
    // are evicted whole because retaining their recorded vertex streams would cost more than the mesh.
    public int distantContraptionGpuBudgetMiB = 48;
    public int distantKineticGpuBudgetMiB = 32;
    // Aero/sable: render simulated contraptions within this % of voxy's LOD render distance.
    public int simulatedContraptionRenderDistancePercent = 50;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount()/1.5, 1);
    public float subDivisionSize = 28;
    public int skyFogDistance = 96;
    public float fogIntensity = 1.0f;
    public float fogDensity = 0.0f;
    // Scales voxy's self-defined LOD fog distance (100 = fog reaches full at voxy's render edge).
    public int fogDistancePercent = 100;
    public boolean adaptCloudDistance = true;
    public int cloudDistance = 0;
    public boolean dontUseSodiumBuilderThreads = false;
    public int renderPressure = 2;
    // Small overlap retained for the existing section-boundary safety path.
    public int lodBoundaryBuffer = 1;
    public boolean enableLodBoundaryFade = true;
    public int lodBoundaryFadeLength = 16;
    public int lodBoundaryInset = 8;
    public int earthCurveRatio = 0;
    // FakeSight-style chunk request extension. 127 is the protocol ceiling because vanilla writes
    // ClientInformation.viewDistance as a signed byte.
    public boolean enableExtendedRequestDistance = false;
    public boolean followLodRequestDistance = true;
    public int requestDistance = 48;
    public String ssaoMode;
    public boolean useEnvironmentalFog = true;
    public String leafLodMode = "balanced";
    public boolean enableFarPlayerRendering = true;
    public boolean enableFarVehicleRendering = true;
    public boolean renderFarPlayerNames = true;
    public int farPlayerAnimationDistance = 1024;
    public boolean shareFarPlayerPosition = true;
    // Persisted separately from the user-facing master switch so the credits message is shown once.
    public boolean joinMessageShown = false;

    public int getRequestDistance() {
        int requested = this.followLodRequestDistance
                ? Math.round(this.sectionRenderDistance * 32.0f)
                : this.requestDistance;
        return Math.clamp(requested, MIN_REQUEST_DISTANCE, MAX_REQUEST_DISTANCE);
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

    // EclipticSeasons compat: recolor LOD terrain with seasonal snow (master switch for the eclipticseasons mixins).
    public boolean eclipticSeasonsSnowLod = true;
    // EclipticSeasons compat: re-import region LODs when the season changes.
    public boolean eclipticSeasonsLodAutoReload = false;
    // EclipticSeasons compat: rebuild the LOD renderer when the season changes.
    public boolean eclipticSeasonsReloadOnSeasonChange = false;

    // Print the build, its maintainer and the fork's repository to chat on world join.
    public boolean showJoinMessage = true;

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
        this.lodBoundaryBuffer = Math.clamp(this.lodBoundaryBuffer, 0, 4);
        this.lodBoundaryFadeLength = Math.clamp(this.lodBoundaryFadeLength, 8, 64);
        this.lodBoundaryInset = Math.clamp(this.lodBoundaryInset, 8, 32);
        this.setLeafLodMode(this.getLeafLodMode());
        this.farPlayerAnimationDistance = Math.clamp(this.farPlayerAnimationDistance, 0, 32768);
    }

    public void save() {
        if (!VoxyCommon.isAvailable()) {
            Logger.info("Not saving config since voxy is unavalible");
            this.syncSableContraptionRenderDistance();
            this.syncDistantTrainConfig();
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

        this.syncSableContraptionRenderDistance();
        this.syncDistantTrainConfig();
    }

    private static void backupInvalidConfig(Path path) {
        try {
            Path backup = path.resolveSibling(path.getFileName() + ".invalid");
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Logger.error("Failed to back up invalid Voxy config", e);
        }
    }

    // Aero/sable: push the live render-distance/percent to the sable contraption-LOD calculator.
    // SableContraptionRenderDistance has no sable-type references, so this is safe even without sable.
    public void syncSableContraptionRenderDistance() {
        SableContraptionRenderDistance.updateClientConfig(
                this.isRenderingEnabled() && this.sableLodRendering,
                this.sectionRenderDistance,
                this.simulatedContraptionRenderDistancePercent
        );
    }

    // Create: push the distant-train enable flag + render distance to the server-side sampler bridge,
    // so the pose-stream window matches what the client actually draws (integrated server bandwidth).
    public void syncDistantTrainConfig() {
        DistantTrainConfig.updateClientConfig(
                this.isRenderingEnabled() && this.distantTrains,
                this.createRenderDistance(this.distantTrainMaxChunks)
        );
    }

    // Effective distant-render radius in blocks. sectionRenderDistance counts 32-chunk sections, so
    // the block radius is 32 * 16 * sectionRenderDistance.
    public double createLodRadius() {
        return 32.0 * 16.0 * this.sectionRenderDistance;
    }

    public double createRenderDistance(int maxChunks) {
        double lod = this.createLodRadius();
        return maxChunks > 0 ? Math.min(maxChunks * 16.0, lod) : lod;
    }

    private static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get().resolve("voxy-config.json");
    }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
    }
}
