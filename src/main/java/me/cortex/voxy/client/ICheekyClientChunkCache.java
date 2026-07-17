package me.cortex.voxy.client;

import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

public interface ICheekyClientChunkCache {
   @Nullable LevelChunk voxy$cheekyGetChunk(int var1, int var2);
}
