package me.cortex.voxy.commonImpl.mixin.sable;

import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap")
public interface SableSubLevelHoldingChunkMapAccessor {
    @Accessor(value = "loadedHoldingChunks", remap = false)
    Long2ObjectMap<SubLevelHoldingChunk> voxy$getLoadedHoldingChunks();
}
