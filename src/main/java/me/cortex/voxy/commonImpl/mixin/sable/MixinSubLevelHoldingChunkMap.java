package me.cortex.voxy.commonImpl.mixin.sable;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.Collection;
import me.cortex.voxy.commonImpl.compat.sable.SableHoldingChunkIndexSavedData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap")
public class MixinSubLevelHoldingChunkMap {
    @Shadow(remap = false)
    @Final
    private ServerLevel level;

    @Inject(method = "processUnload", at = @At("TAIL"), remap = false)
    private void voxy$markProcessedHoldingChunk(ChunkPos chunkPos, Collection<ServerSubLevel> unloadedSubLevels, CallbackInfo ci) {
        voxy$markOrUnmark(chunkPos);
    }

    @Inject(method = "moveToUnloaded", at = @At("TAIL"), remap = false)
    private void voxy$markMovedHoldingChunk(ServerSubLevel subLevel, ChunkPos chunkPos, CallbackInfo ci) {
        voxy$markOrUnmark(chunkPos);
    }

    @Inject(method = "getOrLoadHoldingChunk", at = @At("RETURN"), remap = false)
    private void voxy$rememberLoadedHoldingChunk(ChunkPos chunkPos, boolean createIfMissing, CallbackInfoReturnable<SubLevelHoldingChunk> cir) {
        voxy$markOrUnmark(chunkPos, cir.getReturnValue());
    }

    @Inject(method = "queueDeletion", at = @At("TAIL"), remap = false)
    private void voxy$unmarkDeletedHoldingChunk(ServerSubLevel subLevel, CallbackInfo ci) {
        GlobalSavedSubLevelPointer pointer = subLevel.getLastSerializationPointer();
        if (pointer != null) {
            voxy$markOrUnmark(pointer.chunkPos());
        }
    }

    private void voxy$markOrUnmark(ChunkPos chunkPos) {
        Long2ObjectMap<SubLevelHoldingChunk> loadedHoldingChunks = ((SableSubLevelHoldingChunkMapAccessor) this).voxy$getLoadedHoldingChunks();
        if (loadedHoldingChunks == null) {
            return;
        }

        SubLevelHoldingChunk holdingChunk = loadedHoldingChunks.get(chunkPos.toLong());
        if (holdingChunk == null) {
            return;
        }

        voxy$markOrUnmark(chunkPos, holdingChunk);
    }

    private void voxy$markOrUnmark(ChunkPos chunkPos, SubLevelHoldingChunk holdingChunk) {
        if (holdingChunk == null || (holdingChunk.getSubLevelPointers().isEmpty() && !voxy$hasLoadedHoldingSubLevels(holdingChunk))) {
            SableHoldingChunkIndexSavedData.unmark(this.level, chunkPos);
        } else {
            SableHoldingChunkIndexSavedData.mark(this.level, chunkPos);
        }
    }

    private static boolean voxy$hasLoadedHoldingSubLevels(SubLevelHoldingChunk holdingChunk) {
        return holdingChunk.getLoadedHoldingSubLevels().iterator().hasNext();
    }
}
