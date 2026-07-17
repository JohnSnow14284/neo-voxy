package me.cortex.voxy.common.config.storage.other;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.LongConsumer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.MemoryBuffer;
import net.minecraft.world.level.levelgen.RandomSupport;

public class FragmentedStorageBackendAdaptor extends StorageBackend {
   private final StorageBackend[] backends;

   public FragmentedStorageBackendAdaptor(StorageBackend... backends) {
      this.backends = backends;
      int len = backends.length;
      if ((len & len - 1) != 0) {
         throw new IllegalArgumentException("Backend count not a power of 2");
      }
   }

   private int getSegmentId(long key) {
      return (int)(RandomSupport.mixStafford13(RandomSupport.mixStafford13(key) ^ key) & this.backends.length - 1);
   }

   @Override
   public void iteratePositions(int level, LongConsumer consumer) {
      for (StorageBackend backend : this.backends) {
         backend.iteratePositions(level, consumer);
      }
   }

   @Override
   public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
      return this.backends[this.getSegmentId(key)].getSectionData(key, scratch);
   }

   @Override
   public void setSectionData(long key, MemoryBuffer data) {
      this.backends[this.getSegmentId(key)].setSectionData(key, data);
   }

   @Override
   public void deleteSectionData(long key) {
      this.backends[this.getSegmentId(key)].deleteSectionData(key);
   }

   @Override
   public void putIdMapping(int id, ByteBuffer data) {
      for (StorageBackend backend : this.backends) {
         backend.putIdMapping(id, data);
      }
   }

   @Override
   public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
      Object2IntOpenHashMap<Int2ObjectOpenHashMap<FragmentedStorageBackendAdaptor.EqualingArray>> verification = new Object2IntOpenHashMap();
      Int2ObjectOpenHashMap<FragmentedStorageBackendAdaptor.EqualingArray> any = null;

      for (StorageBackend backend : this.backends) {
         Int2ObjectOpenHashMap<byte[]> mappings = backend.getIdMappingsData();
         if (!mappings.isEmpty()) {
            Int2ObjectOpenHashMap<FragmentedStorageBackendAdaptor.EqualingArray> repackaged = new Int2ObjectOpenHashMap(mappings.size());
            ObjectIterator var9 = mappings.int2ObjectEntrySet().iterator();

            while (var9.hasNext()) {
               Entry<byte[]> entry = (Entry<byte[]>)var9.next();
               repackaged.put(entry.getIntKey(), new FragmentedStorageBackendAdaptor.EqualingArray((byte[])entry.getValue()));
            }

            verification.addTo(repackaged, 1);
            any = repackaged;
         }
      }

      if (any == null) {
         return new Int2ObjectOpenHashMap();
      } else if (verification.size() != 1) {
         Logger.error("Error id mapping not matching across all fragments, attempting to recover");
         it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<Int2ObjectOpenHashMap<FragmentedStorageBackendAdaptor.EqualingArray>> maxEntry = null;
         ObjectIterator var14 = verification.object2IntEntrySet().iterator();

         while (var14.hasNext()) {
            it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<Int2ObjectOpenHashMap<FragmentedStorageBackendAdaptor.EqualingArray>> entry = (it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<Int2ObjectOpenHashMap<FragmentedStorageBackendAdaptor.EqualingArray>>)var14.next();
            if (maxEntry == null) {
               maxEntry = entry;
            } else if (maxEntry.getIntValue() < entry.getIntValue()) {
               maxEntry = entry;
            }
         }

         Int2ObjectOpenHashMap<FragmentedStorageBackendAdaptor.EqualingArray> mapping = (Int2ObjectOpenHashMap<FragmentedStorageBackendAdaptor.EqualingArray>)maxEntry.getKey();
         Int2ObjectOpenHashMap<byte[]> out = new Int2ObjectOpenHashMap(mapping.size());
         ObjectIterator var19 = mapping.int2ObjectEntrySet().iterator();

         while (var19.hasNext()) {
            Entry<FragmentedStorageBackendAdaptor.EqualingArray> entry = (Entry<FragmentedStorageBackendAdaptor.EqualingArray>)var19.next();
            out.put(entry.getIntKey(), ((FragmentedStorageBackendAdaptor.EqualingArray)entry.getValue()).bytes);
         }

         return out;
      } else {
         Int2ObjectOpenHashMap<byte[]> out = new Int2ObjectOpenHashMap(any.size());
         ObjectIterator var13 = any.int2ObjectEntrySet().iterator();

         while (var13.hasNext()) {
            Entry<FragmentedStorageBackendAdaptor.EqualingArray> entry = (Entry<FragmentedStorageBackendAdaptor.EqualingArray>)var13.next();
            out.put(entry.getIntKey(), ((FragmentedStorageBackendAdaptor.EqualingArray)entry.getValue()).bytes);
         }

         return out;
      }
   }

   @Override
   public void flush() {
      for (StorageBackend db : this.backends) {
         db.flush();
      }
   }

   @Override
   public void close() {
      for (StorageBackend db : this.backends) {
         db.close();
      }
   }

   @Override
   public List<StorageBackend> getChildBackends() {
      return List.of(this.backends);
   }

   public static class Config extends StorageConfig {
      public List<StorageConfig> backends = new ArrayList<>();

      @Override
      public List<StorageConfig> getChildStorageConfigs() {
         return new ArrayList<>(this.backends);
      }

      @Override
      public StorageBackend build(ConfigBuildCtx ctx) {
         StorageBackend[] builtBackends = new StorageBackend[this.backends.size()];

         for (int i = 0; i < this.backends.size(); i++) {
            builtBackends[i] = this.backends.get(i).build(ctx);
         }

         return new FragmentedStorageBackendAdaptor(builtBackends);
      }

      public static String getConfigTypeName() {
         return "FragmentationAdaptor";
      }
   }

   public static class Config2 extends StorageConfig {
      public StorageConfig delegate;
      public String basePath;
      public int count;

      @Override
      public List<StorageConfig> getChildStorageConfigs() {
         return new ArrayList<>(Collections.singleton(this.delegate));
      }

      @Override
      public StorageBackend build(ConfigBuildCtx ctx) {
         StorageBackend[] builtBackends = new StorageBackend[this.count];

         for (int i = 0; i < this.count; i++) {
            ctx.pushPath(this.basePath + "_" + i);
            builtBackends[i] = this.delegate.build(ctx);
            ctx.popPath();
         }

         return new FragmentedStorageBackendAdaptor(builtBackends);
      }

      public static String getConfigTypeName() {
         return "AutoFragmentationAdaptor";
      }
   }

   private record EqualingArray(byte[] bytes) {
      @Override
      public boolean equals(Object obj) {
         return Arrays.equals(this.bytes, ((FragmentedStorageBackendAdaptor.EqualingArray)obj).bytes);
      }

      @Override
      public int hashCode() {
         return Arrays.hashCode(this.bytes);
      }
   }
}
