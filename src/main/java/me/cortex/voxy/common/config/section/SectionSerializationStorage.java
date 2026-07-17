package me.cortex.voxy.common.config.section;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.LongConsumer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldSection;

public class SectionSerializationStorage extends SectionStorage {
   public static final int BIGGEST_SERIALIZED_SECTION_SIZE = 524296;
   private final StorageBackend backend;
   private static final ThreadLocalMemoryBuffer MEMORY_CACHE = new ThreadLocalMemoryBuffer(525320L);

   public SectionSerializationStorage(StorageBackend storageBackend) {
      this.backend = storageBackend;
   }

   @Override
   public int loadSection(WorldSection into) {
      MemoryBuffer data = this.backend.getSectionData(into.key, MEMORY_CACHE.get().createUntrackedUnfreeableReference());
      if (data != null) {
         if (!SaveLoadSystem3.deserialize(into, data)) {
            this.backend.deleteSectionData(into.key);
            Arrays.fill(into._unsafeGetRawDataArray(), 0L);
            Logger.error("Section " + into.lvl + ", " + into.x + ", " + into.y + ", " + into.z + " was unable to load, removing");
            return -1;
         } else {
            return 0;
         }
      } else {
         return 1;
      }
   }

   @Override
   public void saveSection(WorldSection section) {
      MemoryBuffer saveData = SaveLoadSystem3.serialize(section);
      this.backend.setSectionData(section.key, saveData);
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
