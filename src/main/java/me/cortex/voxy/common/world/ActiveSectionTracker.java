package me.cortex.voxy.common.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.other.Mapper;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

public class ActiveSectionTracker {

    public interface SectionLoader {
        int load(WorldSection section);
    }

    private static final class VolatileHolder<T> {
        private static final VarHandle PRE_ACQUIRE_COUNT;
        private static final VarHandle POST_ACQUIRE_COUNT;
        static {
            try {
                PRE_ACQUIRE_COUNT = MethodHandles.lookup().findVarHandle(VolatileHolder.class, "preAcquireCount", int.class);
                POST_ACQUIRE_COUNT = MethodHandles.lookup().findVarHandle(VolatileHolder.class, "postAcquireCount", int.class);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        public volatile int preAcquireCount;
        public volatile int postAcquireCount;
        public volatile T obj;
    }

    private final AtomicInteger loadedSections = new AtomicInteger();
    private final Long2ObjectOpenHashMap<VolatileHolder<WorldSection>>[] loadedSectionCache;
    private final StampedLock[] locks;
    private final SectionLoader loader;

    private final int lruSize;
    private final StampedLock lruLock = new StampedLock();
    private final Long2ObjectLinkedOpenHashMap<WorldSection> lruSecondaryCache;

    @Nullable
    public final WorldEngine engine;

    public ActiveSectionTracker(int numSlicesBits, SectionLoader loader, int cacheSize) {
        this(numSlicesBits, loader, cacheSize, null);
    }

    @SuppressWarnings("unchecked")
    public ActiveSectionTracker(int numSlicesBits, SectionLoader loader, int cacheSize, WorldEngine engine) {
        this.engine = engine;

        this.loader = loader;
        this.loadedSectionCache = new Long2ObjectOpenHashMap[1 << numSlicesBits];
        this.lruSecondaryCache = new Long2ObjectLinkedOpenHashMap<>(cacheSize);
        this.locks = new StampedLock[1 << numSlicesBits];
        this.lruSize = cacheSize;
        for (int i = 0; i < this.loadedSectionCache.length; i++) {
            this.loadedSectionCache[i] = new Long2ObjectOpenHashMap<>(1024);
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
        var cache = this.loadedSectionCache[index];
        final var lock = this.locks[index];
        VolatileHolder<WorldSection> holder = null;
        boolean isLoader = false;
        WorldSection section = null;

        {
            long stamp = lock.readLock();
            holder = cache.get(key);
            if (holder != null) {
                section = holder.obj;
                if (section != null) {
                    section.acquire();
                    lock.unlockRead(stamp);
                    return section;
                }
                lock.unlockRead(stamp);
            } else {
                holder = new VolatileHolder<>();
                long ws = lock.tryConvertToWriteLock(stamp);
                if (ws == 0) {
                    lock.unlockRead(stamp);
                    stamp = lock.writeLock();
                } else {
                    stamp = ws;
                }
                var existingHolder = cache.putIfAbsent(key, holder);
                lock.unlockWrite(stamp);
                if (existingHolder == null) {
                    isLoader = true;
                } else {
                    holder = existingHolder;
                }
            }
        }

        if (isLoader) {
            this.loadedSections.incrementAndGet();
            long stamp2 = lock.readLock();
            long stamp = this.lruLock.writeLock();
            section = this.lruSecondaryCache.remove(key);

            WorldSection removal = null;
            if (section == null && !this.lruSecondaryCache.isEmpty()
                    && this.lruSize + 100 < this.lruSecondaryCache.size() + this.getLoadedCacheCount()) {
                removal = this.lruSecondaryCache.removeFirst();
            }

            this.lruLock.unlockWrite(stamp);
            if (section != null) {
                section.primeForReuse();
                section.acquire(1);
            }
            lock.unlockRead(stamp2);

            if (removal != null) {
                removal._releaseArray();
            }
        } else {
            VolatileHolder.PRE_ACQUIRE_COUNT.getAndAdd(holder, 1);
        }

        if (isLoader) {
            int status = 0;
            if (section == null) {
                section = new WorldSection(WorldEngine.getLevel(key),
                        WorldEngine.getX(key),
                        WorldEngine.getY(key),
                        WorldEngine.getZ(key),
                        this);

                status = this.loader.load(section);

                if (status < 0) {
                    Logger.error("Unable to load section " + section.key + " setting to air");
                    status = 1;
                }

                if (status == 1) {
                    int sky = 15;
                    int block = 0;
                    Arrays.fill(section.data, Mapper.composeMappingId((byte) (sky|(block<<4)),0,0));
                }
                section.acquire(1);
            }
            int preAcquireCount = (int) VolatileHolder.PRE_ACQUIRE_COUNT.getAndSet(holder, 0);
            section.acquire(preAcquireCount);
            VolatileHolder.POST_ACQUIRE_COUNT.set(holder, preAcquireCount);

            VarHandle.storeStoreFence();
            holder.obj = section;
            VarHandle.releaseFence();
            if (nullOnEmpty && status == 1) {
                section.release();
                return null;
            }
            return section;
        } else {
            VarHandle.fullFence();
            while ((section = holder.obj) == null) {
                VarHandle.fullFence();
                Thread.onSpinWait();
                Thread.yield();
            }

            if (0<((int)VolatileHolder.POST_ACQUIRE_COUNT.getAndAdd(holder, -1))) {
                return section;
            } else {
                if (section.tryAcquire()) {
                    return section;
                }

                return this.acquire(key, nullOnEmpty);
            }
        }
    }

    void tryUnload(WorldSection section, int hints) {
        final WorldEngine world = this.engine;
        if (world != null) {
            world.lastActiveTime = System.currentTimeMillis();
        }

        // A release can race both a writer and the asynchronous saver. Re-evaluate the
        // state in-place rather than recursively entering tryUnload from another release.
        for (;;) {
            if (world != null && section.shouldSave()) {
                if (!section.tryAcquire()) {
                    return;
                }

                // Recheck after acquiring a reference. A saver may have claimed the section
                // between the first shouldSave() test and tryAcquire().
                VarHandle.loadLoadFence();
                if (!section.shouldSave()) {
                    section.release(false, hints);
                    continue;
                }

                // Outside the cache lock we may apply queue back-pressure and help drain it.
                // A successful enqueue owns the reference acquired above.
                if (world.saveSection(section, false, true)) {
                    return;
                }

                // Another saver won the queue claim. Drop only our temporary reference and
                // inspect the state again; the winning queue entry keeps the section alive.
                section.release(false, hints);
                continue;
            }

            if (section.getRefCount() != 0) {
                return;
            }

            int index = this.getCacheArrayIndex(section.key);
            final var cache = this.loadedSectionCache[index];
            final var lock = this.locks[index];
            WorldSection removedSection = null;

            long stamp = lock.writeLock();

            // acquire() uses the same striped lock. This second check closes the window
            // between the lock-free ref-count test above and obtaining the write lock.
            if (section.getRefCount() != 0) {
                lock.unlockWrite(stamp);
                return;
            }

            VarHandle.loadLoadFence();
            if (world != null && section.shouldSave()) {
                if (!section.tryAcquire()) {
                    lock.unlockWrite(stamp);
                    continue;
                }

                // Never block or drain the save service while holding the section-cache lock.
                if (world.saveSection(section, true, true)) {
                    lock.unlockWrite(stamp);
                    return;
                }

                // A competing queue claim appeared while the lock was held. Keep unloading
                // out of this critical section and retry after releasing our temporary ref.
                section.release(false, hints);
                lock.unlockWrite(stamp);
                continue;
            }

            if (section.getRefCount() == 0 && section.trySetFreed()) {
                var cached = cache.remove(section.key);
                var obj = cached.obj;
                if (obj == null) {
                    throw new IllegalStateException("This should be impossible: " + WorldEngine.pprintPos(section.key) + " secObj: " + System.identityHashCode(section));
                }
                if (obj != section) {
                    throw new IllegalStateException("Removed section not the same as the referenced section in the cache: cached: " + obj + " got: " + section + " A: " + WorldSection.ATOMIC_STATE_HANDLE.get(obj) + " B: " + WorldSection.ATOMIC_STATE_HANDLE.get(section));
                }
                removedSection = section;
            }

            WorldSection evictedSection = null;
            if (removedSection != null) {
                long lruStamp = this.lruLock.writeLock();
                lock.unlockWrite(stamp);

                WorldSection duplicate = this.lruSecondaryCache.put(section.key, section);
                if (duplicate != null) {
                    this.lruLock.unlockWrite(lruStamp);
                    throw new IllegalStateException("duplicate sections in cache is impossible");
                }
                if (this.lruSize < this.lruSecondaryCache.size()) {
                    evictedSection = this.lruSecondaryCache.removeFirst();
                }
                this.lruLock.unlockWrite(lruStamp);
            } else {
                lock.unlockWrite(stamp);
            }

            if (evictedSection != null) {
                evictedSection._releaseArray();
            }
            if (removedSection != null) {
                this.loadedSections.decrementAndGet();
            }
            return;
        }
    }

    private int getCacheArrayIndex(long pos) {
        return (int) (mixStafford13(pos) & (this.loadedSectionCache.length-1));
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
        var tracker = new ActiveSectionTracker(6, a->0, 2<<10);
        var bean = tracker.acquire(0, 0, 0, 9, false);
        var bean2 = tracker.acquire(1, 0, 0, 0, false);
        System.out.println("Target obj:" + System.identityHashCode(bean2));
        bean2.release();
        Thread[] ts = new Thread[10];
        for (int i = 0; i < ts.length;i++) {
            int tid = i;
            ts[i] = new Thread(()->{
                try {
                    for (int j = 0; j < 5000; j++) {
                        if (true) {
                            var section = tracker.acquire(0, 0, 0, 0, false);
                            section.acquire();
                            var section2 = tracker.acquire(1, 0, 0, 0, false);
                            section.release();
                            section.release();
                            section2.release();
                        }
                        if (true) {

                            var section = tracker.acquire(0, 0, 0, 0, false);
                            var section2 = tracker.acquire(1, 0, 0, 0, false);
                            section2.release();
                            section.release();
                        }
                        if (true) {
                            tracker.acquire(1, 0, 0, 0, false).release();
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Thread " + tid, e);
                }
            });
            ts[i].start();
        }
        for (var t : ts) {
            t.join();
        }
    }
}
