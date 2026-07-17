package me.cortex.voxy.common.config.storage.rocksdb;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongConsumer;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.rocksdb.AbstractImmutableNativeReference;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompactionPriority;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.DataBlockIndexType;
import org.rocksdb.HyperClockCache;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteOptions;

public class RocksDBStorageBackend extends StorageBackend {
   private final RocksDB db;
   private final ColumnFamilyHandle worldSections;
   private final ColumnFamilyHandle idMappings;
   private final ReadOptions sectionReadOps;
   private final WriteOptions sectionWriteOps;
   private final List<AbstractImmutableNativeReference> closeList = new ArrayList<>();

   public RocksDBStorageBackend(String path) {
      RocksDB.loadLibrary();
      ColumnFamilyOptions cfOpts = new ColumnFamilyOptions().setCompressionType(CompressionType.ZSTD_COMPRESSION).optimizeForSmallDb();
      ColumnFamilyOptions cfWorldSecOpts = new ColumnFamilyOptions()
         .setCompressionType(CompressionType.NO_COMPRESSION)
         .setCompactionPriority(CompactionPriority.MinOverlappingRatio)
         .setLevelCompactionDynamicLevelBytes(true)
         .optimizeForPointLookup(128L);
      HyperClockCache bCache = new HyperClockCache(134217728L, 0L, 4, false);
      BloomFilter filter = new BloomFilter(10.0);
      cfWorldSecOpts.setTableFormatConfig(
         new BlockBasedTableConfig()
            .setCacheIndexAndFilterBlocksWithHighPriority(true)
            .setBlockCache(bCache)
            .setDataBlockHashTableUtilRatio(0.75)
            .setDataBlockIndexType(DataBlockIndexType.kDataBlockBinaryAndHash)
            .setFilterPolicy(filter)
      );
      List<ColumnFamilyDescriptor> cfDescriptors = Arrays.asList(
         new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts),
         new ColumnFamilyDescriptor("world_sections".getBytes(), cfWorldSecOpts),
         new ColumnFamilyDescriptor("id_mappings".getBytes(), cfOpts)
      );
      DBOptions options = new DBOptions()
         .setAvoidUnnecessaryBlockingIO(true)
         .setIncreaseParallelism(2)
         .setCreateIfMissing(true)
         .setCreateMissingColumnFamilies(true)
         .setMaxTotalWalSize(134217728L);
      List<ColumnFamilyHandle> handles = new ArrayList<>();

      try {
         this.db = RocksDB.open(options, path, cfDescriptors, handles);
         this.sectionReadOps = new ReadOptions();
         this.sectionWriteOps = new WriteOptions();
         this.closeList.add(options);
         this.closeList.add(cfOpts);
         this.closeList.add(cfWorldSecOpts);
         this.closeList.add(this.sectionReadOps);
         this.closeList.add(this.sectionWriteOps);
         this.closeList.add(filter);
         this.closeList.add(bCache);
         this.closeList.addAll(handles);
         this.worldSections = handles.get(1);
         this.idMappings = handles.get(2);
         this.db.flushWal(true);
      } catch (RocksDBException var10) {
         throw new RuntimeException(var10);
      }
   }

   @Override
   public void iteratePositions(int level, LongConsumer consumer) {
      MemoryStack stack = MemoryStack.stackPush();

      try {
         RocksIterator iter = this.db.newIterator(this.worldSections, this.sectionReadOps);

         try {
            ByteBuffer keyBuff = stack.calloc(8);
            long keyBuffPtr = MemoryUtil.memAddress(keyBuff);
            if (level != -1) {
               ByteBuffer seekBuff = stack.calloc(8);
               MemoryUtil.memPutLong(MemoryUtil.memAddress(seekBuff), Long.reverseBytes(Integer.toUnsignedLong(level) << 60));
               iter.seek(seekBuff);
            } else {
               iter.seekToFirst();
            }

            while (iter.isValid()) {
               keyBuff.clear();
               iter.key(keyBuff);
               long key = Long.reverseBytes(MemoryUtil.memGetLong(keyBuffPtr));
               if (level != -1 && WorldEngine.getLevel(key) != level) {
                  break;
               }

               consumer.accept(key);
               iter.next();
            }
         } catch (Throwable var12) {
            if (iter != null) {
               try {
                  iter.close();
               } catch (Throwable var11) {
                  var12.addSuppressed(var11);
               }
            }

            throw var12;
         }

         if (iter != null) {
            iter.close();
         }
      } catch (Throwable var13) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var10) {
               var13.addSuppressed(var10);
            }
         }

         throw var13;
      }

      if (stack != null) {
         stack.close();
      }
   }

   @Override
   public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
      try {
         MemoryStack stack = MemoryStack.stackPush();

         MemoryBuffer var11;
         label48: {
            try {
               ByteBuffer buffer = stack.malloc(8);
               MemoryUtil.memPutLong(MemoryUtil.memAddress(buffer), Long.reverseBytes(swizzlePos(key)));
               int result = this.db.get(this.worldSections, this.sectionReadOps, buffer, MemoryUtil.memByteBuffer(scratch.address, (int)scratch.size));
               if (result == -1) {
                  var11 = null;
                  break label48;
               }

               var11 = scratch.subSize(result);
            } catch (Throwable var9) {
               if (stack != null) {
                  try {
                     stack.close();
                  } catch (Throwable var8) {
                     var9.addSuppressed(var8);
                  }
               }

               throw var9;
            }

            if (stack != null) {
               stack.close();
            }

            return var11;
         }

         if (stack != null) {
            stack.close();
         }

         return var11;
      } catch (RocksDBException var10) {
         throw new RuntimeException(var10);
      }
   }

   @Override
   public void setSectionData(long key, MemoryBuffer data) {
      try {
         MemoryStack stack = MemoryStack.stackPush();

         try {
            ByteBuffer keyBuff = stack.calloc(8);
            MemoryUtil.memPutLong(MemoryUtil.memAddress(keyBuff), Long.reverseBytes(swizzlePos(key)));
            this.db.put(this.worldSections, this.sectionWriteOps, keyBuff, data.asByteBuffer());
         } catch (Throwable var8) {
            if (stack != null) {
               try {
                  stack.close();
               } catch (Throwable var7) {
                  var8.addSuppressed(var7);
               }
            }

            throw var8;
         }

         if (stack != null) {
            stack.close();
         }
      } catch (RocksDBException var9) {
         throw new RuntimeException(var9);
      }
   }

   @Override
   public void deleteSectionData(long key) {
      try {
         this.db.delete(this.worldSections, longToBytes(swizzlePos(key)));
      } catch (RocksDBException var4) {
         throw new RuntimeException(var4);
      }
   }

   @Override
   public void putIdMapping(int id, ByteBuffer data) {
      try {
         byte[] buffer = new byte[data.remaining()];
         data.get(buffer);
         data.rewind();
         this.db.put(this.idMappings, intToBytes(id), buffer);
      } catch (RocksDBException var4) {
         throw new RuntimeException(var4);
      }
   }

   @Override
   public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
      Int2ObjectOpenHashMap<byte[]> out = new Int2ObjectOpenHashMap();
      RocksIterator iterator = this.db.newIterator(this.idMappings);

      try {
         iterator.seekToFirst();

         while (iterator.isValid()) {
            out.put(bytesToInt(iterator.key()), iterator.value());
            iterator.next();
         }
      } catch (Throwable var6) {
         if (iterator != null) {
            try {
               iterator.close();
            } catch (Throwable var5) {
               var6.addSuppressed(var5);
            }
         }

         throw var6;
      }

      if (iterator != null) {
         iterator.close();
      }

      return out;
   }

   @Override
   public void flush() {
      try {
         this.db.flushWal(true);
      } catch (RocksDBException var2) {
         throw new RuntimeException(var2);
      }
   }

   @Override
   public void close() {
      this.flush();
      this.closeList.forEach(AbstractImmutableNativeReference::close);

      try {
         this.db.closeE();
      } catch (RocksDBException var2) {
         throw new RuntimeException(var2);
      }
   }

   private static byte[] intToBytes(int i) {
      return new byte[]{(byte)(i >> 24), (byte)(i >> 16), (byte)(i >> 8), (byte)i};
   }

   private static int bytesToInt(byte[] i) {
      return Byte.toUnsignedInt(i[0]) << 24 | Byte.toUnsignedInt(i[1]) << 16 | Byte.toUnsignedInt(i[2]) << 8 | Byte.toUnsignedInt(i[3]);
   }

   private static byte[] longToBytes(long l) {
      byte[] result = new byte[8];

      for (int i = 7; i >= 0; i--) {
         result[i] = (byte)(l & 255L);
         l >>= 8;
      }

      return result;
   }

   private static long bytesToLong(byte[] b) {
      long result = 0L;

      for (int i = 0; i < 8; i++) {
         result <<= 8;
         result |= b[i] & 255;
      }

      return result;
   }

   private static long swizzlePos(long key) {
      return key;
   }

   public static class Config extends StorageConfig {
      @Override
      public StorageBackend build(ConfigBuildCtx ctx) {
         return new RocksDBStorageBackend(ctx.ensurePathExists(ctx.substituteString(ctx.resolvePath())));
      }

      public static String getConfigTypeName() {
         return "RocksDB";
      }
   }
}
