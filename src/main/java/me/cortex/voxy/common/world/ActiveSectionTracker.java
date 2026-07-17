package me.cortex.voxy.common.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.other.Mapper;
import org.jetbrains.annotations.Nullable;

public final class ActiveSectionTracker {
   private final AtomicInteger loadedSections = new AtomicInteger();
   private final Long2ObjectOpenHashMap<ActiveSectionTracker.VolatileHolder<WorldSection>>[] loadedSectionCache;
   private final StampedLock[] locks;
   private final ActiveSectionTracker.SectionLoader loader;
   private final int lruSize;
   private final StampedLock lruLock = new StampedLock();
   private final Long2ObjectLinkedOpenHashMap<WorldSection> lruSecondaryCache;
   @Nullable
   public final WorldEngine engine;

   public ActiveSectionTracker(int numSlicesBits, ActiveSectionTracker.SectionLoader loader, int cacheSize) {
      this(numSlicesBits, loader, cacheSize, null);
   }

   public ActiveSectionTracker(int numSlicesBits, ActiveSectionTracker.SectionLoader loader, int cacheSize, WorldEngine engine) {
      this.engine = engine;
      this.loader = loader;
      this.loadedSectionCache = new Long2ObjectOpenHashMap[1 << numSlicesBits];
      this.lruSecondaryCache = new Long2ObjectLinkedOpenHashMap(cacheSize);
      this.locks = new StampedLock[1 << numSlicesBits];
      this.lruSize = cacheSize;

      for (int i = 0; i < this.loadedSectionCache.length; i++) {
         this.loadedSectionCache[i] = new Long2ObjectOpenHashMap(1024);
         this.locks[i] = new StampedLock();
      }
   }

   public WorldSection acquire(int lvl, int x, int y, int z, boolean nullOnEmpty) {
      return this.acquire(WorldEngine.getWorldSectionId(lvl, x, y, z), nullOnEmpty);
   }

   public WorldSection acquire(long key, boolean nullOnEmpty) {
      if (this.engine != null) {
         this.engine.lastActiveTime = System.currentTimeMillis();
      }

      int index = this.getCacheArrayIndex(key);
      Long2ObjectOpenHashMap<ActiveSectionTracker.VolatileHolder<WorldSection>> cache = this.loadedSectionCache[index];
      StampedLock lock = this.locks[index];
      ActiveSectionTracker.VolatileHolder<WorldSection> holder = null;
      boolean isLoader = false;
      WorldSection section = null;
      long stamp = lock.readLock();
      holder = (ActiveSectionTracker.VolatileHolder<WorldSection>)cache.get(key);
      if (holder != null) {
         section = holder.obj;
         if (section != null) {
            section.acquire();
            lock.unlockRead(stamp);
            return section;
         }

         lock.unlockRead(stamp);
      } else {
         holder = new ActiveSectionTracker.VolatileHolder<>();
         long ws = lock.tryConvertToWriteLock(stamp);
         if (ws == 0L) {
            lock.unlockRead(stamp);
            stamp = lock.writeLock();
         } else {
            stamp = ws;
         }

         ActiveSectionTracker.VolatileHolder<WorldSection> eHolder = (ActiveSectionTracker.VolatileHolder<WorldSection>)cache.putIfAbsent(key, holder);
         lock.unlockWrite(stamp);
         if (eHolder == null) {
            isLoader = true;
         } else {
            holder = eHolder;
         }
      }

      if (isLoader) {
         this.loadedSections.incrementAndGet();
         stamp = lock.readLock();
         long stampx = this.lruLock.writeLock();
         section = (WorldSection)this.lruSecondaryCache.remove(key);
         WorldSection removal = null;
         if (section == null && !this.lruSecondaryCache.isEmpty() && this.lruSize + 100 < this.lruSecondaryCache.size() + this.getLoadedCacheCount()) {
            removal = (WorldSection)this.lruSecondaryCache.removeFirst();
         }

         this.lruLock.unlockWrite(stampx);
         if (section != null) {
            section.primeForReuse();
            section.acquire(1);
         }

         lock.unlockRead(stamp);
         if (removal != null) {
            removal._releaseArray();
         }
      } else {
         ActiveSectionTracker.VolatileHolder.PRE_ACQUIRE_COUNT.getAndAdd((ActiveSectionTracker.VolatileHolder)holder, (int)1);
      }

      if (isLoader) {
         int status = 0;
         if (section == null) {
            section = new WorldSection(WorldEngine.getLevel(key), WorldEngine.getX(key), WorldEngine.getY(key), WorldEngine.getZ(key), this);
            status = this.loader.load(section);
            if (status < 0) {
               Logger.error("Unable to load section " + section.key + " setting to air");
               status = 1;
            }

            if (status == 1) {
               int sky = 15;
               int block = 0;
               Arrays.fill(section.data, Mapper.composeMappingId((byte)(sky | block << 4), 0, 0));
            }

            section.acquire(1);
         }

         int preAcquireCount = (int)ActiveSectionTracker.VolatileHolder.PRE_ACQUIRE_COUNT.getAndSet((ActiveSectionTracker.VolatileHolder)holder, (int)0);
         section.acquire(preAcquireCount);
         ActiveSectionTracker.VolatileHolder.POST_ACQUIRE_COUNT.set((ActiveSectionTracker.VolatileHolder)holder, (int)preAcquireCount);
         VarHandle.storeStoreFence();
         holder.obj = section;
         VarHandle.releaseFence();
         if (nullOnEmpty && status == 1) {
            section.release();
            return null;
         } else {
            return section;
         }
      } else {
         VarHandle.fullFence();

         while ((section = holder.obj) == null) {
            VarHandle.fullFence();
            Thread.onSpinWait();
            Thread.yield();
         }

         if (0 < (int)ActiveSectionTracker.VolatileHolder.POST_ACQUIRE_COUNT.getAndAdd((ActiveSectionTracker.VolatileHolder)holder, (int)-1)) {
            return section;
         } else {
            return section.tryAcquire() ? section : this.acquire(key, nullOnEmpty);
         }
      }
   }

   void tryUnload(WorldSection section, int hints) {
      if (this.engine != null) {
         this.engine.lastActiveTime = System.currentTimeMillis();
      }

      if (section.shouldSave() && this.engine != null) {
         if (section.tryAcquire()) {
            VarHandle.loadLoadFence();
            if (section.shouldSave()) {
               if (this.engine.saveSection(section, false, true)) {
                  return;
               }

               Logger.info("section raced to into save queue, we lost");
               section.release(true, hints);
            } else {
               Logger.warn("section raced to save queue, we lost");
               section.release(true, hints);
            }
         } else if (section.shouldSave()) {
            Logger.error("failed to acquire section, but we need to save, this is really bad");
         } else {
            Logger.info("raced section");
         }
      } else if (section.getRefCount() == 0) {
         int index = this.getCacheArrayIndex(section.key);
         Long2ObjectOpenHashMap<ActiveSectionTracker.VolatileHolder<WorldSection>> cache = this.loadedSectionCache[index];
         WorldSection sec = null;
         StampedLock lock = this.locks[index];
         long stamp = lock.writeLock();
         if (section.getRefCount() != 0) {
            lock.unlockWrite(stamp);
         } else {
            boolean shouldRetryExit = false;
            VarHandle.loadLoadFence();
            if (this.engine != null && section.shouldSave()) {
               if (!section.tryAcquire()) {
                  throw new IllegalStateException("Section was dirty but is also unloaded, this is very bad");
               }

               if (!this.engine.saveSection(section, true, true)) {
                  shouldRetryExit |= true;
                  section.release(false, hints);
               }
            }

            if (shouldRetryExit) {
               lock.unlockWrite(stamp);
               this.tryUnload(section, hints);
            } else {
               if (section.getRefCount() == 0 && section.trySetFreed()) {
                  ActiveSectionTracker.VolatileHolder<WorldSection> cached = (ActiveSectionTracker.VolatileHolder<WorldSection>)cache.remove(section.key);
                  WorldSection obj = cached.obj;
                  if (obj == null) {
                     throw new IllegalStateException(
                        "This should be impossible: " + WorldEngine.pprintPos(section.key) + " secObj: " + System.identityHashCode(section)
                     );
                  }

                  if (obj != section) {
                     throw new IllegalStateException(
                        "Removed section not the same as the referenced section in the cache: cached: "
                           + obj
                           + " got: "
                           + section
                           + " A: "
                           + (Object)WorldSection.ATOMIC_STATE_HANDLE.get((WorldSection)obj)
                           + " B: "
                           + (Object)WorldSection.ATOMIC_STATE_HANDLE.get((WorldSection)section)
                     );
                  }

                  sec = section;
               }

               WorldSection aa = null;
               if (sec != null) {
                  long stamp2 = this.lruLock.writeLock();
                  lock.unlockWrite(stamp);
                  WorldSection a = (WorldSection)this.lruSecondaryCache.put(section.key, section);
                  if (a != null) {
                     throw new IllegalStateException("duplicate sections in cache is impossible");
                  }

                  if (this.lruSize < this.lruSecondaryCache.size()) {
                     aa = (WorldSection)this.lruSecondaryCache.removeFirst();
                  }

                  this.lruLock.unlockWrite(stamp2);
               } else {
                  lock.unlockWrite(stamp);
               }

               if (aa != null) {
                  aa._releaseArray();
               }

               if (sec != null) {
                  this.loadedSections.decrementAndGet();
               }
            }
         }
      }
   }

   private int getCacheArrayIndex(long pos) {
      return (int)(mixStafford13(pos) & this.loadedSectionCache.length - 1);
   }

   public static long mixStafford13(long seed) {
      seed = (seed ^ seed >>> 30) * -4658895280553007687L;
      seed = (seed ^ seed >>> 27) * -7723592293110705685L;
      return seed ^ seed >>> 31;
   }

   public int getLoadedCacheCount() {
      return this.loadedSections.get();
   }

   public int getSecondaryCacheSize() {
      return this.lruSecondaryCache.size();
   }

   public static void main(String[] args) throws InterruptedException {
      ActiveSectionTracker tracker = new ActiveSectionTracker(6, a -> 0, 2048);
      WorldSection bean = tracker.acquire(0, 0, 0, 9, false);
      WorldSection bean2 = tracker.acquire(1, 0, 0, 0, false);
      System.out.println("Target obj:" + System.identityHashCode(bean2));
      bean2.release();
      Thread[] ts = new Thread[10];

      for (int i = 0; i < ts.length; i++) {
         int tid = i;
         ts[i] = new Thread(() -> {
            try {
               for (int j = 0; j < 5000; j++) {
                  WorldSection section = tracker.acquire(0, 0, 0, 0, false);
                  section.acquire();
                  WorldSection section2 = tracker.acquire(1, 0, 0, 0, false);
                  section.release();
                  section.release();
                  section2.release();
                  section = tracker.acquire(0, 0, 0, 0, false);
                  section2 = tracker.acquire(1, 0, 0, 0, false);
                  section2.release();
                  section.release();
                  tracker.acquire(1, 0, 0, 0, false).release();
               }
            } catch (Exception var5x) {
               throw new RuntimeException("Thread " + tid, var5x);
            }
         });
         ts[i].start();
      }

      for (Thread t : ts) {
         t.join();
      }
   }

   public interface SectionLoader {
      int load(WorldSection var1);
   }

   private static final class VolatileHolder<T> {
      private static final VarHandle PRE_ACQUIRE_COUNT;
      private static final VarHandle POST_ACQUIRE_COUNT;
      public volatile int preAcquireCount;
      public volatile int postAcquireCount;
      public volatile T obj;

      static {
         try {
            PRE_ACQUIRE_COUNT = MethodHandles.lookup().findVarHandle(ActiveSectionTracker.VolatileHolder.class, "preAcquireCount", int.class);
            POST_ACQUIRE_COUNT = MethodHandles.lookup().findVarHandle(ActiveSectionTracker.VolatileHolder.class, "postAcquireCount", int.class);
         } catch (IllegalAccessException | NoSuchFieldException var1) {
            throw new RuntimeException(var1);
         }
      }
   }
}
