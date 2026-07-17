package me.cortex.voxy.common.config.storage.lmdb;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.lmdb.MDBVal;

public class LMDBStorageBackend extends StorageBackend {
   private static final long GROW_SIZE = 33554432L;
   private final AtomicInteger accessingCounts = new AtomicInteger();
   private final ReentrantLock resizeLock = new ReentrantLock();
   private final LMDBInterface dbi;
   private final LMDBInterface.Database sectionDatabase;
   private final LMDBInterface.Database idMappingDatabase;

   public LMDBStorageBackend(String file) {
      this.dbi = new LMDBInterface.Builder().setMaxDbs(2).open(file, 0).fetch();
      this.dbi.setMapSize(33554432L);
      this.sectionDatabase = this.dbi.createDb("world_sections");
      this.idMappingDatabase = this.dbi.createDb("id_mapping");
   }

   private void growEnv() {
      long size = this.dbi.getMapSize() + 33554432L;
      Logger.info("Growing DBI env size to: " + size + " bytes");
      this.dbi.setMapSize(size);
   }

   private <T> T resizingTransaction(Supplier<T> transaction) {
      while (true) {
         try {
            return this.synchronizedTransaction(transaction);
         } catch (Throwable var3) {
            if (!var3.getMessage().startsWith("Code: -30792")) {
               throw var3;
            }

            if (this.resizeLock.tryLock()) {
               while (this.accessingCounts.get() != 0) {
                  Thread.onSpinWait();
               }

               this.growEnv();
               this.resizeLock.unlock();
            }
         }
      }
   }

   private <T> T synchronizedTransaction(Supplier<T> transaction) {
      Object var2;
      try {
         this.accessingCounts.getAndAdd(1);

         while (this.resizeLock.isLocked()) {
            this.accessingCounts.getAndAdd(-1);

            while (this.resizeLock.isLocked()) {
               Thread.onSpinWait();
            }

            this.accessingCounts.getAndAdd(1);
         }

         var2 = transaction.get();
      } finally {
         this.accessingCounts.getAndAdd(-1);
      }

      return (T)var2;
   }

   @Override
   public void iteratePositions(int level, LongConsumer consumer) {
      throw new IllegalStateException("Not yet implemented");
   }

   @Override
   public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
      return this.synchronizedTransaction(() -> this.sectionDatabase.transaction(131072, transaction -> {
         ByteBuffer buff = transaction.stack.malloc(8);
         buff.putLong(0, key);
         ByteBuffer bb = transaction.get(buff);
         if (bb == null) {
            return null;
         } else {
            UnsafeUtil.memcpy(MemoryUtil.memAddress(bb), scratch.address, bb.remaining());
            return scratch.subSize(bb.remaining());
         }
      }));
   }

   @Override
   public void setSectionData(long key, MemoryBuffer data) {
      this.resizingTransaction(() -> this.sectionDatabase.transaction(transaction -> {
         ByteBuffer keyBuff = transaction.stack.malloc(8);
         keyBuff.putLong(0, key);
         transaction.put(keyBuff, MemoryUtil.memByteBuffer(data.address, (int)data.size), 0);
         return null;
      }));
   }

   @Override
   public void deleteSectionData(long key) {
      this.synchronizedTransaction(() -> this.sectionDatabase.transaction(transaction -> {
         ByteBuffer keyBuff = transaction.stack.malloc(8);
         keyBuff.putLong(0, key);
         transaction.del(keyBuff);
         return null;
      }));
   }

   @Override
   public synchronized void putIdMapping(int id, ByteBuffer data) {
      this.resizingTransaction(() -> this.idMappingDatabase.transaction(transaction -> {
         ByteBuffer keyBuff = transaction.stack.malloc(4);
         keyBuff.putInt(0, id);
         transaction.put(keyBuff, data, 0);
         return null;
      }));
   }

   @Override
   public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
      return this.synchronizedTransaction(() -> {
         Int2ObjectOpenHashMap<byte[]> mapping = new Int2ObjectOpenHashMap();
         this.idMappingDatabase.transaction(131072, transaction -> {
            try (Cursor cursor = transaction.createCursor()) {
               MDBVal keyPtr = MDBVal.malloc(transaction.stack);
               MDBVal valPtr = MDBVal.malloc(transaction.stack);

               while (cursor.get(8, keyPtr, valPtr) != -30798) {
                  int keyVal = keyPtr.mv_data().getInt(0);
                  byte[] data = new byte[(int)valPtr.mv_size()];
                  Objects.requireNonNull(valPtr.mv_data()).get(data);
                  if (mapping.put(keyVal, data) != null) {
                     throw new IllegalStateException("Multiple mappings to same id");
                  }
               }
            }

            return null;
         });
         return mapping;
      });
   }

   @Override
   public void flush() {
      this.dbi.flush(true);
   }

   @Override
   public void close() {
      this.sectionDatabase.close();
      this.idMappingDatabase.close();
      this.dbi.close();
   }

   public static class Config extends StorageConfig {
      @Override
      public StorageBackend build(ConfigBuildCtx ctx) {
         return new LMDBStorageBackend(ctx.ensurePathExists(ctx.substituteString(ctx.resolvePath())));
      }

      public static String getConfigTypeName() {
         return "LMDB";
      }
   }
}
