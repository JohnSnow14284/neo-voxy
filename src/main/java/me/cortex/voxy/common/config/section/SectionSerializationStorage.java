package me.cortex.voxy.common.config.section;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldSection;

import java.nio.ByteBuffer;
import java.util.function.LongConsumer;

public class SectionSerializationStorage extends SectionStorage {
    public static final int BIGGEST_SERIALIZED_SECTION_SIZE = 32 * 32 * 32 * 8 * 2 + 8;

    private final StorageBackend backend;
    public SectionSerializationStorage(StorageBackend storageBackend) {
        this.backend = storageBackend;
    }

    private static final ThreadLocalMemoryBuffer MEMORY_CACHE = new ThreadLocalMemoryBuffer(BIGGEST_SERIALIZED_SECTION_SIZE + 1024);

    public int loadSection(WorldSection into) {
        var data = this.backend.getSectionData(into.key, MEMORY_CACHE.get().createUntrackedUnfreeableReference());
        if (data != null) {
            if (!SaveLoadSystem3.deserialize(into, data)) {
                this.backend.deleteSectionData(into.key);
                //TODO: regenerate the section from children
                //No fill here: returning -1 makes the tracker force status 1 and set the section to
                //uniform air itself, so filling an array we are about to discard was dead work (and it
                //would now needlessly materialise one).
                Logger.error("Section " + into.lvl + ", " + into.x + ", " + into.y + ", " + into.z + " was unable to load, removing");
                return -1;
            } else {
                return 0;
            }
        } else {
            //TODO: if we need to fetch an lod from a server, send the request here and block until the request is finished
            // the response should be put into the local db so that future data can just use that
            // the server can also send arbitrary updates to the client for arbitrary lods
            return 1;
        }
    }


    @Override
    public boolean supportsAuxTable(String table) {
        return this.backend.supportsAuxTable(table);
    }

    @Override
    public void putAux(String table, long key, byte[] value) {
        this.backend.putAux(table, key, value);
    }

    @Override
    public byte[] getAux(String table, long key) {
        return this.backend.getAux(table, key);
    }

    @Override
    public void deleteAux(String table, long key) {
        this.backend.deleteAux(table, key);
    }

    @Override
    public void forEachAux(String table, StorageBackend.AuxEntryConsumer consumer) {
        this.backend.forEachAux(table, consumer);
    }

    @Override
    public void saveSection(WorldSection section) {
        var saveData = SaveLoadSystem3.serialize(section);
        this.backend.setSectionData(section.key, saveData);
        //Note that savedData isnt freed (the save system uses a cache)
    }

    @Override
    public SectionSaveBatch createSaveBatch() {
        var inner = this.backend.createSectionWriteBatch();
        return new SectionSaveBatch() {
            @Override
            public void add(WorldSection section) {
                //Serialise here, exactly where saveSection would have: the serializer returns a
                //thread-local scratch buffer, so the batch must consume it before this returns
                inner.put(section.key, SaveLoadSystem3.serialize(section));
            }

            @Override public long dataSize() { return inner.dataSize(); }
            @Override public void commit() { inner.commit(); }
            @Override public void close() { inner.close(); }
        };
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        this.backend.putIdMapping(id, data);
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        return this.backend.getIdMappingsData();
    }

    @Override
    public void flush() {
        this.backend.flush();
    }

    @Override
    public void close() {
        this.backend.close();
    }

    @Override
    public void iteratePositions(int level, LongConsumer consumer) {
        this.backend.iteratePositions(level, consumer);
    }

    public static class Config extends SectionStorageConfig {
        public StorageConfig storage;

        @Override
        public SectionStorage build(ConfigBuildCtx ctx) {
            return new SectionSerializationStorage(this.storage.build(ctx));
        }

        public static String getConfigTypeName() {
            return "Serializer";
        }
    }
}
