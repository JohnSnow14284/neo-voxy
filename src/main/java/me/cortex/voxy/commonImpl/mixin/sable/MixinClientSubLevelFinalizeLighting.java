package me.cortex.voxy.commonImpl.mixin.sable;

import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.ClientSubLevel")
public class MixinClientSubLevelFinalizeLighting {
    @Unique
    private static boolean voxy$skyLightFallbackUnavailable;


    @Shadow(remap = false)
    private int latestSkyLightScale;

    @Shadow(remap = false)
    public native ClientLevel getLevel();

    @Shadow(remap = false)
    public native BoundingBox3dc boundingBox();

    @Inject(method = "setFinalized", at = @At("TAIL"), remap = false)
    private void voxy$invalidateInitialSkyLightScale(CallbackInfo ci) {
        this.latestSkyLightScale = -1;
    }

    @Inject(method = "computeSubLevelSkyLight", at = @At("RETURN"), cancellable = true, remap = false)
    private void voxy$useVoxySkyLightWhenVanillaChunkIsMissing(Pose3dc pose, CallbackInfoReturnable<Integer> cir) {
        ClientLevel level = this.getLevel();
        int fallbackSkyLight = voxy$getChunkBackedSubLevelSkyLight(level, pose, this.boundingBox());
        if (fallbackSkyLight >= 0) {
            cir.setReturnValue(fallbackSkyLight);
        }
    }

    @Unique
    private static int voxy$getChunkBackedSubLevelSkyLight(ClientLevel level, Pose3dc pose, @Nullable BoundingBox3dc bounds) {
        if (voxy$skyLightFallbackUnavailable) {
            return -1;
        }

        try {
            int skyLight;
            if (bounds == null || bounds.volume() < 9.0D) {
                var position = pose.position();
                skyLight = voxy$sampleChunkBackedSkyLight(level, position.x(), position.y(), position.z());
                if (skyLight <= 0) {
                    skyLight = Math.max(skyLight, voxy$sampleChunkBackedSkyLight(level, position.x(), position.y() + 1.0D, position.z()));
                }
                if (skyLight <= 0) {
                    skyLight = Math.max(skyLight, voxy$sampleChunkBackedSkyLight(level, position.x(), position.y() - 1.0D, position.z()));
                }
            } else {
                Vector3d center = bounds.center(new Vector3d());
                double sampleY = center.y() + 0.1D;
                skyLight = -1;
                skyLight = Math.max(skyLight, voxy$sampleChunkBackedSkyLight(level, center.x(), sampleY, center.z()));
                skyLight = Math.max(skyLight, voxy$sampleChunkBackedSkyLight(level, bounds.minX(), sampleY, bounds.minZ()));
                skyLight = Math.max(skyLight, voxy$sampleChunkBackedSkyLight(level, bounds.maxX(), sampleY, bounds.minZ()));
                skyLight = Math.max(skyLight, voxy$sampleChunkBackedSkyLight(level, bounds.minX(), sampleY, bounds.maxZ()));
                skyLight = Math.max(skyLight, voxy$sampleChunkBackedSkyLight(level, bounds.maxX(), sampleY, bounds.maxZ()));
            }

            return skyLight;
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Sable sky light fallback after Voxy light lookup failed", e);
            voxy$skyLightFallbackUnavailable = true;
            return -1;
        }
    }

    @Unique
    private static int voxy$sampleChunkBackedSkyLight(ClientLevel level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());

        if (!(level.getChunkSource() instanceof ICheekyClientChunkCache chunkCache)) {
            return -1;
        }

        if (chunkCache.voxy$cheekyGetChunk(chunkX, chunkZ) != null) {
            return level.getBrightness(LightLayer.SKY, pos);
        }

        //Straight to the voxel store: a chunk the client does not have has no brightness to read, and
        //this is the only remaining source.
        return voxy$readVoxySkyLight(level, pos);
    }

    @Unique
    private static int voxy$readVoxySkyLight(ClientLevel level, BlockPos pos) {
        int sectionY = SectionPos.blockToSectionCoord(pos.getY());
        if (sectionY < level.getMinSection() || sectionY >= level.getMaxSection()) {
            return -1;
        }

        WorldEngine engine = WorldIdentifier.ofEngineNullable(level);
        if (engine == null) {
            return -1;
        }

        int sectionX = SectionPos.blockToSectionCoord(pos.getX());
        int sectionZ = SectionPos.blockToSectionCoord(pos.getZ());
        WorldSection section = engine.acquireIfExists(0, sectionX >> 1, sectionY >> 1, sectionZ >> 1);
        if (section == null) {
            return -1;
        }

        try {
            int index = WorldSection.getIndex(pos.getX() & 31, pos.getY() & 31, pos.getZ() & 31);
            //Per-block query - must never materialise a 256KiB array for one voxel
            return Mapper.getLightId(section.get(index)) & 15;
        } finally {
            section.release();
        }
    }
}
