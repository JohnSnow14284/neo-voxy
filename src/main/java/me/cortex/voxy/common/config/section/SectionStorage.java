package me.cortex.voxy.common.config.section;

import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.config.IStoredSectionPositionIterator;
import me.cortex.voxy.common.world.WorldSection;

public abstract class SectionStorage implements IMappingStorage, IStoredSectionPositionIterator {
    public abstract int loadSection(WorldSection into);

    public abstract void saveSection(WorldSection section);

    //A group of section saves applied together. add() serialises immediately (the serializer hands back
    //a thread-local scratch buffer), only the write is deferred to commit(). Thread-confined.
    public interface SectionSaveBatch extends AutoCloseable {
        void add(WorldSection section);
        long dataSize();
        void commit();
        @Override void close();
    }

    //Default: save entries one at a time, i.e. exactly today's behaviour.
    public SectionSaveBatch createSaveBatch() {
        return new SectionSaveBatch() {
            @Override
            public void add(WorldSection section) {
                SectionStorage.this.saveSection(section);
            }

            @Override public long dataSize() { return 0; }
            @Override public void commit() {}
            @Override public void close() {}
        };
    }
}
