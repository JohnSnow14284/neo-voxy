package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientChunkCache.Storage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.ModList;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public class MixinClientChunkCache implements ICheekyClientChunkCache {
   @Unique
   private static final boolean BOBBY_INSTALLED = ModList.get().isLoaded("bobby");
   @Shadow
   private volatile Storage storage;

   @Override
   public @Nullable LevelChunk voxy$cheekyGetChunk(int x, int z) {
      LevelChunk chunk = this.storage.getChunk(this.storage.getIndex(x, z));
      if (chunk == null) {
         return null;
      } else {
         return chunk.getPos().x() == x && chunk.getPos().z() == z ? chunk : null;
      }
   }

   @Inject(method = "drop", at = @At("HEAD"))
   public void voxy$captureChunkBeforeUnload(ChunkPos pos, CallbackInfo ci) {
      if (VoxyConfig.CONFIG.ingestEnabled && BOBBY_INSTALLED) {
         LevelChunk chunk = this.voxy$cheekyGetChunk(pos.x(), pos.z());
         if (chunk != null) {
            VoxelIngestService.tryAutoIngestChunk(chunk);
         }
      }
   }
}
