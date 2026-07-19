package me.cortex.voxy.commonImpl.compat.sable;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

public final class SableHoldingChunkIndexSavedData extends SavedData {
    private static final String FILE_ID = "voxy_sable_holding_chunks";
    private static final String HOLDING_CHUNKS_KEY = "holding_chunks";

    private final LongSet holdingChunks = new LongOpenHashSet();

    private static SableHoldingChunkIndexSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        SableHoldingChunkIndexSavedData data = new SableHoldingChunkIndexSavedData();
        for (long chunk : tag.getLongArray(HOLDING_CHUNKS_KEY)) {
            data.holdingChunks.add(chunk);
        }
        return data;
    }

    public static SableHoldingChunkIndexSavedData getOrLoad(ServerLevel level) {
        return level.getChunkSource().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SableHoldingChunkIndexSavedData::new, SableHoldingChunkIndexSavedData::load, DataFixTypes.LEVEL),
                FILE_ID
        );
    }

    public static void mark(ServerLevel level, ChunkPos chunkPos) {
        SableHoldingChunkIndexSavedData data = getOrLoad(level);
        if (data.holdingChunks.add(chunkPos.toLong())) {
            data.setDirty();
        }
    }

    public static void unmark(ServerLevel level, ChunkPos chunkPos) {
        SableHoldingChunkIndexSavedData data = getOrLoad(level);
        if (data.holdingChunks.remove(chunkPos.toLong())) {
            data.setDirty();
        }
    }

    public LongSet copyChunks() {
        return new LongOpenHashSet(this.holdingChunks);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray(HOLDING_CHUNKS_KEY, this.holdingChunks.toLongArray());
        return tag;
    }
}
