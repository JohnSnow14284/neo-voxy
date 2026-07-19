package me.cortex.voxy.common.world;


import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

//Represents a loaded world section at a specific detail level
// holds a 32x32x32 region of detail
public final class WorldSection {
    public static final int SECTION_VOLUME = 32*32*32;
    public static final boolean VERIFY_WORLD_SECTION_EXECUTION = VoxyCommon.isVerificationFlagOn("verifyWorldSectionExecution");


    static final VarHandle ATOMIC_STATE_HANDLE;
    private static final VarHandle NON_EMPTY_CHILD_HANDLE;
    private static final VarHandle NON_EMPTY_BLOCK_HANDLE;
    private static final VarHandle IN_SAVE_QUEUE_HANDLE;
    private static final VarHandle IS_DIRTY_HANDLE;
    private static final VarHandle DATA_HANDLE;

    static {
        try {
            DATA_HANDLE = MethodHandles.lookup().findVarHandle(WorldSection.class, "data", long[].class);
            ATOMIC_STATE_HANDLE = MethodHandles.lookup().findVarHandle(WorldSection.class, "atomicState", int.class);
            NON_EMPTY_CHILD_HANDLE = MethodHandles.lookup().findVarHandle(WorldSection.class, "nonEmptyChildren", byte.class);
            NON_EMPTY_BLOCK_HANDLE = MethodHandles.lookup().findVarHandle(WorldSection.class, "nonEmptyBlockCount", int.class);
            IN_SAVE_QUEUE_HANDLE = MethodHandles.lookup().findVarHandle(WorldSection.class, "inSaveQueue", boolean.class);
            IS_DIRTY_HANDLE = MethodHandles.lookup().findVarHandle(WorldSection.class, "isDirty", boolean.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }


    //TODO: should make it dynamically adjust the size allowance based on memory pressure/WorldSection allocation rate (e.g. is it doing a world import)
    private static final int ARRAY_REUSE_CACHE_SIZE = 400;//500;//32*32*32*8*ARRAY_REUSE_CACHE_SIZE == number of bytes
    //TODO: maybe just swap this to a ConcurrentLinkedDeque
    private static final AtomicInteger ARRAY_REUSE_CACHE_COUNT = new AtomicInteger(0);
    private static final ConcurrentLinkedDeque<long[]> ARRAY_REUSE_CACHE = new ConcurrentLinkedDeque<>();


    public final int lvl;
    public final int x;
    public final int y;
    public final int z;
    public final long key;


    //Serialized states
    long metadata;
    //null means UNIFORM: every voxel is uniformValue, and no 256KiB array is allocated at all. This is
    //the common case (air above ground, solid underground, unexplored). Materialisation is one-way and
    //happens on the first write that differs, so readers only ever need to snapshot this field once.
    volatile long[] data = null;
    volatile long uniformValue;
    //data == null is a legal state now (uniform), so it can no longer double as the released marker
    private boolean arrayReleased;
    volatile int nonEmptyBlockCount = 0;//Note: only needed for level 0 sections
    volatile byte nonEmptyChildren;

    final ActiveSectionTracker tracker;
    volatile boolean inSaveQueue;
    volatile boolean isDirty;

    //When the first bit is set it means its loaded
    @SuppressWarnings("all")
    private volatile int atomicState = 1;

    WorldSection(int lvl, int x, int y, int z, ActiveSectionTracker tracker) {
        this.lvl = lvl;
        this.x = x;
        this.y = y;
        this.z = z;
        this.key = WorldEngine.getWorldSectionId(lvl, x, y, z);
        this.tracker = tracker;

        //Start uniform - the loaders either fill in a real uniform value or materialise. Nothing is
        //allocated until something actually needs a per-voxel array.
        this.data = null;
        this.uniformValue = Mapper.AIR;
    }

    void primeForReuse() {
        ATOMIC_STATE_HANDLE.set(this, 1);
    }

    public boolean isUniform() {
        return this.data == null;
    }

    public long getUniformValue() {
        return this.uniformValue;
    }

    //Only legal while the section is still unpublished (construction / load), where there is no
    //concurrent reader. Publishing null last makes the uniform value visible before the mode flips.
    public void setUniform(long value) {
        this.uniformValue = value;
        DATA_HANDLE.setRelease(this, null);
    }

    //Single voxel read that never materialises
    public long get(int idx) {
        long[] d = this.data;
        return d == null ? this.uniformValue : d[idx];
    }

    //Snapshot of the backing array, may be null when uniform. Callers must handle null.
    public long[] _rawOrNull() {
        return this.data;
    }

    //Returns the backing array, allocating and filling it with uniformValue if still uniform.
    //Fast path is a plain volatile read - the lock is only ever taken for the one-time uniform ->
    //materialized transition. A CAS-and-discard version wasted a full 32768 long fill on every lost
    //race, and with ten mesh workers all reaching for the same shared neighbour that was ~10% of all
    //materialisations.
    public long[] materialize() {
        long[] d = this.data;
        if (d != null) {
            return d;
        }
        synchronized (this) {
            d = this.data;
            if (d != null) {
                //Another thread materialised while we waited - no work wasted, just note the contention
                me.cortex.voxy.commonImpl.PerfStats.sectionMaterializeContended.increment();
                return d;
            }
            long value = this.uniformValue;
            long[] fresh = ARRAY_REUSE_CACHE.poll();
            if (fresh == null) {
                fresh = new long[SECTION_VOLUME];
            } else {
                ARRAY_REUSE_CACHE_COUNT.decrementAndGet();
            }
            //MUST fill before publishing: arrays out of the reuse pool are never cleared, so a reader
            //that saw the array before the fill would render the previous section's voxels as ghost
            //terrain. The release store pairs with the volatile read above.
            Arrays.fill(fresh, value);
            DATA_HANDLE.setRelease(this, fresh);
            me.cortex.voxy.commonImpl.PerfStats.sectionMaterialized.increment();
            return fresh;
        }
    }

    @Override
    public int hashCode() {
        return ((x*1235641+y)*8127451+z)*918267913+lvl;
    }

    public boolean tryAcquire() {
        int prev, next;
        do {
            prev = (int) ATOMIC_STATE_HANDLE.get(this);
            if ((prev&1) == 0) {
                //The object has been release so early exit
                return false;
            }
            next = prev + 2;
        } while (!ATOMIC_STATE_HANDLE.compareAndSet(this, prev, next));
        return (next&1) != 0;


        /*
        int prev, next;
        do {
            prev = (int) ATOMIC_STATE_HANDLE.get(this);
            next = ((prev&1) != 0)?prev+2:prev;
        } while (!ATOMIC_STATE_HANDLE.compareAndSet(this, prev, next));
        return (next&1) != 0;
         */
    }

    public int acquire() {
        return this.acquire(1);
    }

    public int acquire(int count) {
        int state = ((int)  ATOMIC_STATE_HANDLE.getAndAdd(this, count<<1)) + (count<<1);
        if ((state & 1) == 0) {
            throw new IllegalStateException("Tried to acquire unloaded section: " + WorldEngine.pprintPos(this.key) + " obj: " + System.identityHashCode(this));
        }
        return state>>1;
    }

    public int getRefCount() {
        return ((int)ATOMIC_STATE_HANDLE.get(this))>>1;
    }

    public int release() {
        return release(true, 0);
    }


    public static int RELEASE_HINT_POSSIBLE_REUSE = 1;
    //Unload but specify possible reuse hints
    public int release(int hints) {
        return release(true, hints);
    }

    int release(boolean unload, int hints) {
        int state = ((int) ATOMIC_STATE_HANDLE.getAndAdd(this, -2)) - 2;
        if (state < 1) {
            throw new IllegalStateException("Section got into an invalid state");
        }
        if ((state & 1) == 0) {
            throw new IllegalStateException("Tried releasing a freed section");
        }
        if ((state>>1)==0 && unload) {
            if (this.tracker != null) {
                this.tracker.tryUnload(this, hints);
            } else {
                //This should _ONLY_ ever happen when its an untracked section
                // If it is, try release it
                if (this.trySetFreed()) {
                    this._releaseArray();
                }
            }
        }
        return state>>1;
    }

    //Returns true on success, false on failure
    boolean trySetFreed() {
        int witness = (int) ATOMIC_STATE_HANDLE.compareAndExchange(this, 1, 0);
        if ((witness & 1) == 0 && witness != 0) {
            throw new IllegalStateException("Section marked as free but has refs");
        }
        if (witness == 1 && (this.isDirty || this.inSaveQueue)) {
            throw new IllegalStateException("Section freed while marked as dirty or in the save queue: " + (this.isDirty?"dirty, ":"") + (this.inSaveQueue?"saveQueue":""));
        }
        return witness == 1;
    }

    void _releaseArray() {
        if (VERIFY_WORLD_SECTION_EXECUTION && this.arrayReleased) {
            throw new IllegalStateException("Section array released twice");
        }
        this.arrayReleased = true;
        long[] d = this.data;
        if (d == null) {
            //Never materialised - nothing to return to the pool
            return;
        }
        if (ARRAY_REUSE_CACHE_COUNT.get() < ARRAY_REUSE_CACHE_SIZE) {
            ARRAY_REUSE_CACHE.add(d);
            ARRAY_REUSE_CACHE_COUNT.incrementAndGet();
        }
        this.data = null;
        //Without this the section still answers isUniform() with whatever value it held before it was
        //materialised, so a late read gets a plausible wrong voxel instead of an obvious failure -
        //and a neighbour face slice would be filled with it wholesale.
        this.uniformValue = Mapper.AIR;
    }


    public void assertNotFree() {
        if (VERIFY_WORLD_SECTION_EXECUTION) {
            if ((((int) ATOMIC_STATE_HANDLE.get(this)) & 1) == 0) {
                throw new IllegalStateException();
            }
        }
    }

    public static int getIndex(int x, int y, int z) {
        final int M = (1<<5)-1;
        if (VERIFY_WORLD_SECTION_EXECUTION) {
            if (x < 0 || x > M || y < 0 || y > M || z < 0 || z > M) {
                throw new IllegalArgumentException("Out of bounds: " + x + ", " + y + ", " + z);
            }
        }
        return ((y&M)<<10)|((z&M)<<5)|(x&M);
    }

    public static int getChildIndex(int x, int y, int z) {
        return (x&1)|((y&1)<<2)|((z&1)<<1);
    }

    public byte getNonEmptyChildren() {
        return (byte) NON_EMPTY_CHILD_HANDLE.get(this);
    }

    //Updates this.nonEmptyChildren atomically with respect to the child passed in
    // returns 0 if no change, 1 if it just updated and didnt do a major state change, 2 if it was a major state change (something -> nothing, nothing -> something)
    public int updateEmptyChildState(WorldSection child) {
        int childIdx = getChildIndex(child.x, child.y, child.z);
        byte msk = (byte) (1<<childIdx);
        byte prev, next;
        do {
            prev = this.getNonEmptyChildren();
            next = (byte) ((prev&(~msk))|(child.getNonEmptyChildren()!=0?msk:0));
        } while (!NON_EMPTY_CHILD_HANDLE.compareAndSet(this, prev, next));

        return ((prev!=0)^(next!=0))?2:(prev!=next?1:0);
    }

    public int getNonEmptyBlockCount() {
        return (int) NON_EMPTY_BLOCK_HANDLE.get(this);
    }

    public int addNonEmptyBlockCount(int delta) {
        int count = ((int)NON_EMPTY_BLOCK_HANDLE.getAndAdd(this, delta)) + delta;
        if (VERIFY_WORLD_SECTION_EXECUTION) {
            if (count < 0) {
                throw new IllegalStateException("Count is negative!");
            }
        }
        return count;
    }

    public boolean updateLvl0State() {
        if (VERIFY_WORLD_SECTION_EXECUTION) {
            if (this.lvl != 0) {
                throw new IllegalStateException("Tried updating a level 0 lod when its not level 0: " + WorldEngine.pprintPos(this.key));
            }
        }
        byte prev, next;
        do {
            prev = this.getNonEmptyChildren();
            next = (byte) (((int)NON_EMPTY_BLOCK_HANDLE.get(this))==0?0:0xFF);
        } while (!NON_EMPTY_CHILD_HANDLE.compareAndSet(this, prev, next));
        return prev != next;
    }

    public void _unsafeSetNonEmptyChildren(byte nonEmptyChildren) {
        NON_EMPTY_CHILD_HANDLE.set(this, nonEmptyChildren);
    }

    public static WorldSection _createRawUntrackedUnsafeSection(int lvl, int x, int y, int z) {
        return new WorldSection(lvl, x, y, z, null);
    }

    public void markDirty() {
        IS_DIRTY_HANDLE.getAndSet(this, true);
    }


    public boolean exchangeIsInSaveQueue(boolean state) {
        return ((boolean) IN_SAVE_QUEUE_HANDLE.compareAndExchange(this, !state, state)) == !state;
    }

    //Should only be called by the saving service
    public boolean setNotDirty() {
        return (boolean) IS_DIRTY_HANDLE.getAndSet(this, false);
    }

    public boolean shouldSave() {
        return this.isDirty&&!this.inSaveQueue;
    }

    public boolean isFreed() {
        return (((int)ATOMIC_STATE_HANDLE.get(this))&1)==0;
    }
}