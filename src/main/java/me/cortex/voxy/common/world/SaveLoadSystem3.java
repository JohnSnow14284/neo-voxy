package me.cortex.voxy.common.world;

import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.world.other.Mapper;
import org.lwjgl.system.MemoryUtil;

public class SaveLoadSystem3 {
   public static final int STORAGE_VERSION = 0;
   private static final long METADATA_FORMAT_TAG = 0x564F580100000000L;
   private static final long METADATA_FORMAT_MASK = 0xFFFFFFFF00000000L;
   private static final ThreadLocal<SaveLoadSystem3.SerializationCache> CACHE = ThreadLocal.withInitial(SaveLoadSystem3.SerializationCache::new);

   public static int lin2z(int i) {
      int x = i & 31;
      int y = i >> 10 & 31;
      int z = i >> 5 & 31;
      return Integer.expand(x, 4681) | Integer.expand(y, 9362) | Integer.expand(z, 18724);
   }

   public static int z2lin(int i) {
      int x = Integer.compress(i, 4681);
      int y = Integer.compress(i, 9362);
      int z = Integer.compress(i, 18724);
      return x | y << 10 | z << 5;
   }

   public static MemoryBuffer serialize(WorldSection section) {
      SaveLoadSystem3.SerializationCache cache = CACHE.get();
      long[] data = section.data;
      Long2ShortOpenHashMap LUT = cache.lutMapCache;
      LUT.clear();
      MemoryBuffer buffer = cache.memoryBuffer().createUntrackedUnfreeableReference();
      long ptr = buffer.address;
      MemoryUtil.memPutLong(ptr, section.key);
      ptr += 8L;
      long metadataPtr = ptr;
      ptr += 8L;
      long blockPtr = ptr;
      ptr += 65536L;
      long prev = data[0];
      MemoryUtil.memPutLong(ptr, prev);
      ptr += 8L;
      LUT.put(prev, (short)0);
      short mapping = 0;

      for (long block : data) {
         if (prev != block) {
            prev = block;
            mapping = LUT.putIfAbsent(block, (short)LUT.size());
            if (mapping == -1) {
               mapping = (short)(LUT.size() - 1);
               MemoryUtil.memPutLong(ptr, block);
               ptr += 8L;
            }
         }

         MemoryUtil.memPutShort(blockPtr, mapping);
         blockPtr += 2L;
      }

      if (LUT.size() >= 65536) {
         throw new IllegalStateException();
      } else {
         long metadata = METADATA_FORMAT_TAG;
         metadata |= Integer.toUnsignedLong(LUT.size());
         metadata |= Byte.toUnsignedLong(section.getNonEmptyChildren()) << 16;
         MemoryUtil.memPutLong(metadataPtr, metadata);
         return buffer.subSize(ptr - buffer.address);
      }
   }

   public static boolean deserialize(WorldSection section, MemoryBuffer data) {
      long ptr = data.address;
      long key = MemoryUtil.memGetLong(ptr);
      ptr += 8L;
      if (section.key != key) {
         Logger.error("Decompressed section not the same as requested. got: " + key + " expected: " + section.key);
         return false;
      } else {
         long metadata = MemoryUtil.memGetLong(ptr);
         ptr += 8L;
         section.nonEmptyChildren = (byte)(metadata >>> 16 & 255L);
         long lutBasePtr = ptr + 65536L;
         long[] blockData = section.data;
         int inferredChildren = 0;
         int nonEmptyBlockCount = 0;
         boolean repairChildMetadata = (metadata & METADATA_FORMAT_MASK) != METADATA_FORMAT_TAG;

         for (int i = 0; i < 32768; i++) {
            long block = MemoryUtil.memGetLong(lutBasePtr + Short.toUnsignedLong(MemoryUtil.memGetShort(ptr)) * 8L);
            blockData[i] = block;
            ptr += 2L;
            if ((section.lvl == 0 || repairChildMetadata && inferredChildren != 255) && !Mapper.isAir(block)) {
               nonEmptyBlockCount++;
               if (section.lvl != 0) {
                  int child = (i >> 4 & 1) | (i >> 9 & 1) << 1 | (i >> 14 & 1) << 2;
                  inferredChildren |= 1 << child;
               }
            }
         }

         if (section.lvl == 0) {
            section.nonEmptyBlockCount = nonEmptyBlockCount;
            inferredChildren = nonEmptyBlockCount == 0 ? 0 : 255;
         }

         if (repairChildMetadata) {
            if (section.lvl != 0) {
               inferredChildren |= Byte.toUnsignedInt(section.nonEmptyChildren);
            }

            section.nonEmptyChildren = (byte)inferredChildren;
            section.markDirty();
         }

         ptr = lutBasePtr + (metadata & 65535L) * 8L;
         return true;
      }
   }

   private record SerializationCache(Long2ShortOpenHashMap lutMapCache, MemoryBuffer memoryBuffer) {
      public SerializationCache() {
         this(new Long2ShortOpenHashMap(1024), ThreadLocalMemoryBuffer.create(328704L));
         this.lutMapCache.defaultReturnValue((short)-1);
      }
   }
}
