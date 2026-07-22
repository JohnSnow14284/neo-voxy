package me.cortex.voxy.common.config.storage.other;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.LongConsumer;

public class ReadonlyCachingLayer extends StorageBackend {
    private final StorageBackend cache;
    private final StorageBackend onMiss;

    public ReadonlyCachingLayer(StorageBackend cache, StorageBackend onMiss) {
        this.cache = cache;
        this.onMiss = onMiss;
    }

    @Override
    public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
        var result = this.cache.getSectionData(key, scratch);
        if (result != null) {
            return result;
        }
        result = this.onMiss.getSectionData(key, scratch);
        if (result != null) {
            this.cache.setSectionData(key, result);
        }
        return result;
    }

    @Override
    public void iteratePositions(int level , LongConsumer consumer) {
        throw new IllegalStateException("Not yet implemented");
    }

    @Override
    public void setSectionData(long key, MemoryBuffer data) {
        this.cache.setSectionData(key, data);
    }

    @Override
    public SectionWriteBatch createSectionWriteBatch() {
        return this.cache.createSectionWriteBatch();
    }

    @Override
    public void deleteSectionData(long key) {
        this.cache.deleteSectionData(key);
    }

    //The cache layer is for sections; aux tables live in the backing store so they survive it
    @Override
    public boolean supportsAuxTable(String table) {return this.onMiss.supportsAuxTable(table);}

    @Override
    public void putAux(String table, long key, byte[] value) {this.onMiss.putAux(table, key, value);}

    @Override
    public byte[] getAux(String table, long key) {return this.onMiss.getAux(table, key);}

    @Override
    public void deleteAux(String table, long key) {this.onMiss.deleteAux(table, key);}

    @Override
    public void forEachAux(String table, AuxEntryConsumer consumer) {this.onMiss.forEachAux(table, consumer);}

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        this.cache.putIdMapping(id, data);
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        //TODO: replicate this data onto the cache
        return this.onMiss.getIdMappingsData();
    }

    @Override
    public void flush() {
        this.cache.close();
        this.onMiss.close();
    }

    @Override
    public void close() {
        this.cache.close();
        this.onMiss.close();
    }

    public static class Config extends StorageConfig {
        public StorageConfig cache;
        public StorageConfig onMiss;

        @Override
        public List<StorageConfig> getChildStorageConfigs() {
            return List.of(this.cache, this.onMiss);
        }

        @Override
        public StorageBackend build(ConfigBuildCtx ctx) {
            return new ReadonlyCachingLayer(this.cache.build(ctx), this.onMiss.build(ctx));
        }

        public static String getConfigTypeName() {
            return "ReadonlyCachingLayer";
        }
    }
}
