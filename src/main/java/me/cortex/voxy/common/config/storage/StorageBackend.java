package me.cortex.voxy.common.config.storage;

import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.config.IStoredSectionPositionIterator;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.util.ArrayList;
import java.util.List;

public abstract class StorageBackend implements IMappingStorage, IStoredSectionPositionIterator {

    //Implementation may use the scratch buffer as the return value, it MUST NOT free the scratch buffer
    public abstract MemoryBuffer getSectionData(long key, MemoryBuffer scratch);

    public abstract void setSectionData(long key, MemoryBuffer data);

    //A group of section writes applied together. Thread-confined: created, filled and committed on one
    //thread. put() MUST fully consume data before returning - callers hand in a thread-local scratch
    //buffer that the next serialize overwrites, so a batch can defer the COMMIT but never the read.
    public interface SectionWriteBatch extends AutoCloseable {
        void put(long key, MemoryBuffer data);
        long dataSize();
        //Apply and empty the batch; the batch stays usable afterwards
        void commit();
        @Override void close();
    }

    //Default: replay entries one at a time, i.e. exactly today's behaviour. Backends that can do better
    //(rocksdb) override; the rest need no changes.
    public SectionWriteBatch createSectionWriteBatch() {
        return new SectionWriteBatch() {
            private long bytes;

            @Override
            public void put(long key, MemoryBuffer data) {
                StorageBackend.this.setSectionData(key, data);
                this.bytes += data.size;
            }

            @Override public long dataSize() { return this.bytes; }
            @Override public void commit() { this.bytes = 0; }
            @Override public void close() {}
        };
    }

    public abstract void deleteSectionData(long key);

    public abstract void flush();

    public abstract void close();

    public List<StorageBackend> getChildBackends() {
        return List.of();
    }

    public final List<StorageBackend> collectAllBackends() {
        List<StorageBackend> backends = new ArrayList<>();
        backends.add(this);
        for (var child : this.getChildBackends()) {
            backends.addAll(child.collectAllBackends());
        }
        return backends;
    }
}
