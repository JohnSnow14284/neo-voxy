package me.cortex.voxy.client.mixin.sodium;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
   @Unique
   private static final boolean BOBBY_INSTALLED = ModList.get().isLoaded("bobby");
   @Shadow
   @Final
   private ClientLevel level;
   @Unique
   private long cachedChunkPos = -1L;
   @Unique
   private int cachedChunkStatus;
   @Unique
   private int bottomSectionY;

   @Inject(method = "<init>", at = @At("TAIL"))
   private void voxy$resetChunkTracker(ClientLevel level, int renderDistance, SortBehavior sortBehavior, CommandList commandList, CallbackInfo ci) {
      this.bottomSectionY = this.level.getMinY() >> 4;
   }

   @Inject(method = "renderOutOfGraph", at = @At("HEAD"))
   private void voxy$injectReset1(Viewport viewport, FogParameters fogParameters, CallbackInfo ci) {
      VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
      if (vrs != null && !IrisUtil.irisShadowActive()) {
         vrs.visbleSectionStream.reset();
      }
   }

   @Inject(
      method = "readRenderListFromTree",
      at = @At(
         value = "INVOKE",
         target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/VisibleChunkCollector;<init>(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegionManager;I)V"
      )
   )
   private void voxy$injectReset2(Viewport viewport, FogParameters fogParameters, CallbackInfo ci) {
      VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
      if (vrs != null && !IrisUtil.irisShadowActive()) {
         vrs.visbleSectionStream.reset();
      }
   }

   @Inject(method = "onChunkRemoved", at = @At("HEAD"))
   private void voxy$injectIngest(int x, int z, CallbackInfo ci) {
      if (VoxyConfig.CONFIG.ingestEnabled && !BOBBY_INSTALLED) {
         ICheekyClientChunkCache cccm = (ICheekyClientChunkCache)this.level.getChunkSource();
         if (cccm != null) {
            LevelChunk chunk = cccm.voxy$cheekyGetChunk(x, z);
            if (chunk != null) {
               VoxelIngestService.tryAutoIngestChunk(chunk);
            }
         }
      }
   }

   @Inject(method = "onChunkAdded", at = @At("HEAD"))
   private void voxy$ingestOnAdd(int x, int z, CallbackInfo ci) {
      if (this.level != null && VoxyConfig.CONFIG.ingestEnabled) {
         ClientChunkCache cccm = this.level.getChunkSource();
         if (cccm != null) {
            LevelChunk chunk = cccm.getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk != null) {
               VoxelIngestService.tryAutoIngestChunk(chunk);
            }
         }
      }
   }

   @Redirect(
      method = "updateSectionInfo",
      at = @At(
         value = "INVOKE",
         target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;setInfo(Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;)I"
      )
   )
   private int voxy$updateOnUpload(RenderSection instance, BuiltSectionInfo info) {
      boolean isInvisible = instance.isInvisible();
      int changes = instance.setInfo(info);
      VoxyRenderSystem vrs = null;
      if (isInvisible != instance.isInvisible() && changes != 0 && (vrs = IVoxyRenderSystemHolder.getNullable()) != null) {
         int x = instance.getChunkX();
         int y = instance.getChunkY();
         int z = instance.getChunkZ();
         if (!isInvisible && VoxyConfig.CONFIG.ingestEnabled) {
            Long2IntOpenHashMap tracker = ((AccessorChunkTracker)ChunkTrackerHolder.get(this.level)).getChunkStatus();
            long key = ChunkPos.pack(x, z);
            if (key != this.cachedChunkPos) {
               this.cachedChunkPos = key;
               this.cachedChunkStatus = tracker.getOrDefault(key, 0);
            }

            if (this.cachedChunkStatus == 3) {
               ClientChunkCache cccm = this.level.getChunkSource();
               LevelChunk chunk = cccm.getChunk(x, z, ChunkStatus.FULL, false);
               if (chunk != null) {
                  LevelChunkSection section = chunk.getSection(y - this.bottomSectionY);
                  LevelLightEngine lp = this.level.getLightEngine();
                  SectionPos csp = SectionPos.of(x, y, z);
                  DataLayer blp = lp.getLayerListener(LightLayer.BLOCK).getDataLayerData(csp);
                  DataLayer slp = lp.getLayerListener(LightLayer.SKY).getDataLayerData(csp);
                  VoxelIngestService.rawIngest(vrs.getEngine(), section, x, y, z, blp == null ? null : blp.copy(), slp == null ? null : slp.copy());
               }
            }
         }

         return changes;
      } else {
         return changes;
      }
   }
}
