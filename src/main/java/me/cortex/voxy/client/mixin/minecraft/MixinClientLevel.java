package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientLevel.ClientLevelData;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevel {
   @Unique
   private int bottomSectionY;
   @Shadow
   @Final
   public LevelRenderer levelRenderer;

   @Shadow
   public abstract ClientChunkCache getChunkSource();

   @Inject(method = "<init>", at = @At("TAIL"))
   private void voxy$getBottom(
      ClientPacketListener networkHandler,
      ClientLevelData properties,
      ResourceKey<Level> registryRef,
      Holder<DimensionType> dimensionType,
      int loadDistance,
      int simulationDistance,
      LevelRenderer worldRenderer,
      boolean debugWorld,
      long seed,
      int seaLevel,
      CallbackInfo cir
   ) {
      this.bottomSectionY = ((Level)(Object)this).getMinY() >> 4;
   }

   @Inject(method = "setBlocksDirty", at = @At("TAIL"))
   private void voxy$injectIngestOnStateChange(BlockPos pos, BlockState old, BlockState updated, CallbackInfo cir) {
      if (old != updated) {
         if (updated.isAir()) {
            if (VoxyCommon.getInstance() != null) {
               if (VoxyConfig.CONFIG.ingestEnabled) {
                  Level self = (Level)(Object)this;
                  WorldIdentifier wi = WorldIdentifier.of(self);
                  if (wi != null) {
                     int x = pos.getX() & 15;
                     int y = pos.getY() & 15;
                     int z = pos.getZ() & 15;
                     if (x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15) {
                        SectionPos csp = SectionPos.of(pos);
                        ChunkAccess chunk = self.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
                        if (chunk != null) {
                           LevelChunkSection section = chunk.getSection(csp.y() - this.bottomSectionY);
                           LevelLightEngine lp = self.getLightEngine();
                           DataLayer blp = lp.getLayerListener(LightLayer.BLOCK).getDataLayerData(csp);
                           DataLayer slp = lp.getLayerListener(LightLayer.SKY).getDataLayerData(csp);
                           VoxelIngestService.rawIngest(
                              wi, section, csp.x(), csp.y(), csp.z(), blp == null ? null : blp.copy(), slp == null ? null : slp.copy()
                           );
                        }
                     }
                  }
               }
            }
         }
      }
   }
}
