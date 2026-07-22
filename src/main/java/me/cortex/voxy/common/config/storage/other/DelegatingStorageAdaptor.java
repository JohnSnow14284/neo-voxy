package me.cortex.voxy.common.config.storage.other;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.LongConsumer;

public class DelegatingStorageAdaptor extends StorageBackend {
    protected final StorageBackend delegate;
    public DelegatingStorageAdaptor(StorageBackend delegate) {
        this.delegate = delegate;
    }

    @Override
    public void iteratePositions(int level, LongConsumer consumer) {this.delegate.iteratePositions(level, consumer);}

    @Override
    public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
        return this.delegate.getSectionData(key, scratch);
    }

    @Override
    public void setSectionData(long key, MemoryBuffer data) {
        this.delegate.setSectionData(key, data);
    }

    @Override
    public SectionWriteBatch createSectionWriteBatch() {
        return this.delegate.createSectionWriteBatch();
    }

    @Override
    public void deleteSectionData(long key) {
        this.delegate.deleteSectionData(key);
    }

    @Override
    public boolean supportsAuxTable(String table) {return this.delegate.supportsAuxTable(table);}

    @Override
    public void putAux(String table, long key, byte[] value) {this.delegate.putAux(table, key, value);}

    @Override
    public byte[] getAux(String table, long key) {return this.delegate.getAux(table, key);}

    @Override
    public void deleteAux(String table, long key) {this.delegate.deleteAux(table, key);}

    @Override
    public void forEachAux(String table, AuxEntryConsumer consumer) {this.delegate.forEachAux(table, consumer);}

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        this.delegate.putIdMapping(id, data);
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        return this.delegate.getIdMappingsData();
    }

    @Override
    public void flush() {
        this.delegate.flush();
    }

    @Override
    public void close() {
        this.delegate.close();
    }

    @Override
    public List<StorageBackend> getChildBackends() {
        return List.of(this.delegate);
    }
}
