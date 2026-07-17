package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.concurrent.locks.StampedLock;
import java.util.function.LongConsumer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

public class SectionUpdateRouter implements ISectionWatcher {
   private static final int SLICES = 16;
   private final Long2ByteOpenHashMap[] slices = new Long2ByteOpenHashMap[16];
   private final StampedLock[] locks = new StampedLock[16];
   private LongConsumer initialRenderMeshGen;
   private LongConsumer renderMeshGen;
   private SectionUpdateRouter.IChildUpdate childUpdateCallback;

   public SectionUpdateRouter() {
      for (int i = 0; i < this.slices.length; i++) {
         this.slices[i] = new Long2ByteOpenHashMap();
         this.locks[i] = new StampedLock();
      }
   }

   public void setCallbacks(LongConsumer initialRenderMeshGen, LongConsumer renderMeshGen, SectionUpdateRouter.IChildUpdate childUpdateCallback) {
      if (this.renderMeshGen != null) {
         throw new IllegalStateException();
      } else {
         this.initialRenderMeshGen = initialRenderMeshGen;
         this.renderMeshGen = renderMeshGen;
         this.childUpdateCallback = childUpdateCallback;
      }
   }

   @Override
   public boolean watch(int lvl, int x, int y, int z, int types) {
      return this.watch(WorldEngine.getWorldSectionId(lvl, x, y, z), types);
   }

   @Override
   public boolean watch(long position, int types) {
      int idx = getSliceIndex(position);
      Long2ByteOpenHashMap set = this.slices[idx];
      StampedLock lock = this.locks[idx];
      byte delta = 0;
      long stamp = lock.readLock();
      byte current = set.getOrDefault(position, (byte)0);
      delta = (byte)(current & types);
      current = (byte)(current | (byte)types);
      delta = (byte)(delta ^ (byte)(current & types));
      if (delta != 0) {
         long ws = lock.tryConvertToWriteLock(stamp);
         if (ws == 0L) {
            lock.unlockRead(stamp);
            stamp = lock.writeLock();
            current = set.getOrDefault(position, (byte)0);
            delta = (byte)(current & types);
            current = (byte)(current | (byte)types);
            delta = (byte)(delta ^ (byte)(current & types));
            if (delta != 0) {
               set.put(position, current);
            }
         } else {
            stamp = ws;
            set.put(position, current);
         }
      }

      lock.unlock(stamp);
      if ((delta & types & 1) != 0) {
         this.initialRenderMeshGen.accept(position);
      }

      return delta != 0;
   }

   @Override
   public boolean unwatch(int lvl, int x, int y, int z, int types) {
      return this.unwatch(WorldEngine.getWorldSectionId(lvl, x, y, z), types);
   }

   @Override
   public boolean unwatch(long position, int types) {
      int idx = getSliceIndex(position);
      Long2ByteOpenHashMap set = this.slices[idx];
      StampedLock lock = this.locks[idx];
      long stamp = lock.readLock();
      byte current = set.getOrDefault(position, (byte)0);
      if (current == 0) {
         throw new IllegalStateException("Section pos not in map " + WorldEngine.pprintPos(position));
      } else {
         boolean removed = false;
         if ((current & types) != 0) {
            long ws = lock.tryConvertToWriteLock(stamp);
            if (ws == 0L) {
               lock.unlockRead(stamp);
               stamp = lock.writeLock();
               current = set.getOrDefault(position, (byte)0);
               if (current == 0) {
                  throw new IllegalStateException("Section pos not in map " + WorldEngine.pprintPos(position));
               }
            } else {
               stamp = ws;
            }

            if ((current & types) != 0) {
               current = (byte)(current & (byte)(~types));
               if (current == 0) {
                  set.remove(position);
                  removed = true;
               } else {
                  set.put(position, current);
               }
            }
         }

         lock.unlock(stamp);
         return removed;
      }
   }

   @Override
   public int get(long position) {
      int idx = getSliceIndex(position);
      Long2ByteOpenHashMap set = this.slices[idx];
      StampedLock lock = this.locks[idx];
      long stamp = lock.readLock();
      int ret = set.getOrDefault(position, (byte)0);
      lock.unlockRead(stamp);
      return ret;
   }

   public void forwardEvent(WorldSection section, int type) {
      long position = section.key;
      int idx = getSliceIndex(position);
      Long2ByteOpenHashMap set = this.slices[idx];
      StampedLock lock = this.locks[idx];
      long stamp = lock.readLock();
      byte types = (byte)(set.getOrDefault(position, (byte)0) & type);
      lock.unlockRead(stamp);
      if (types != 0) {
         if ((types & 2) != 0) {
            this.childUpdateCallback.accept(section);
         }

         if ((types & 1) != 0) {
            this.renderMeshGen.accept(section.key);
         }
      }
   }

   public void triggerRemesh(long position) {
      int idx = getSliceIndex(position);
      Long2ByteOpenHashMap set = this.slices[idx];
      StampedLock lock = this.locks[idx];
      long stamp = lock.readLock();
      byte types = set.getOrDefault(position, (byte)0);
      lock.unlockRead(stamp);
      if ((types & 1) != 0) {
         this.renderMeshGen.accept(position);
      }
   }

   private static int getSliceIndex(long value) {
      value = (value ^ value >>> 30) * -4658895280553007687L;
      value = (value ^ value >>> 27) * -7723592293110705685L;
      return (int)((value ^ value >>> 31) & 15L);
   }

   public interface IChildUpdate {
      void accept(WorldSection var1);
   }
}
