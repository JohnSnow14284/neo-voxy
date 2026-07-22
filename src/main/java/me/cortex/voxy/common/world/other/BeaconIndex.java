package me.cortex.voxy.common.world.other;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.Logger;
import net.minecraft.core.BlockPos;

import java.nio.ByteBuffer;

//Where the beacons are, per world. Only the positions: what a beacon's beam looks like - how tall, what
//colours - is a function of the blocks around it, and those already live in the voxel store, so deriving
//the beam at draw time from the same data the terrain came from keeps the two from disagreeing. Storing
//the derived beam instead would need invalidating every time a pane of glass above one changed, which is
//exactly the event we cannot see out where this matters.
//
//Keyed by section so ingest can rewrite a section's entry wholesale: a section is scanned, and whatever
//it turns out to hold replaces whatever was there. No diffing, and a beacon that was mined leaves with
//the section that no longer contains it.
public final class BeaconIndex {
    public static final String TABLE = "beacons";
    private static final byte FORMAT = 1;

    private final SectionStorage storage;
    private final boolean persistent;
    //Section key -> packed local positions. Written from ingest workers, read from the render thread.
    private final Long2ObjectMap<short[]> sections = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>());

    public BeaconIndex(SectionStorage storage) {
        this.storage = storage;
        this.persistent = storage.supportsAuxTable(TABLE);
        if (this.persistent) {
            this.load();
        }
    }

    private void load() {
        int[] count = new int[1];
        try {
            this.storage.forEachAux(TABLE, (key, value) -> {
                short[] locals = decode(value);
                if (locals != null && locals.length != 0) {
                    this.sections.put(key, locals);
                    count[0] += locals.length;
                }
            });
        } catch (Throwable t) {
            Logger.error("Reading the beacon index; continuing without it", t);
            this.sections.clear();
            return;
        }
        if (count[0] != 0) {
            Logger.info("Loaded " + count[0] + " beacon(s) across " + this.sections.size() + " section(s)");
        }
    }

    //Replace everything known about one section. Empty retires the entry rather than storing a zero count,
    //so a world full of ordinary sections costs nothing.
    public void setSection(int sx, int sy, int sz, short[] packedLocals) {
        long key = BlockPos.asLong(sx, sy, sz);
        if (packedLocals == null || packedLocals.length == 0) {
            //Only touch the store if we had something here: the common case is a section that never held a
            //beacon and never will, and issuing a delete for each of those would swamp the write path.
            if (this.sections.remove(key) != null && this.persistent) {
                this.storage.deleteAux(TABLE, key);
            }
            return;
        }
        this.sections.put(key, packedLocals);
        if (this.persistent) {
            this.storage.putAux(TABLE, key, encode(packedLocals));
        }
    }

    //Absolute block positions of every known beacon.
    //
    //Copied under the lock and walked outside it. fastutil's synchronized wrapper does not cover the
    //iterator, and the caller here is the render thread solving a beam per entry - which acquires
    //storage sections and so must not run holding a lock an ingest worker needs to write.
    public void forEach(BeaconConsumer consumer) {
        long[] keys;
        short[][] values;
        synchronized (this.sections) {
            int n = this.sections.size();
            keys = new long[n];
            values = new short[n][];
            int i = 0;
            for (var entry : this.sections.long2ObjectEntrySet()) {
                keys[i] = entry.getLongKey();
                values[i] = entry.getValue();
                i++;
            }
        }
        for (int i = 0; i < keys.length; i++) {
            long key = keys[i];
            int ox = BlockPos.getX(key) << 4;
            int oy = BlockPos.getY(key) << 4;
            int oz = BlockPos.getZ(key) << 4;
            for (short packed : values[i]) {
                consumer.accept(ox + ((packed >> 8) & 0xF), oy + ((packed >> 4) & 0xF), oz + (packed & 0xF));
            }
        }
    }

    public int count() {
        int total = 0;
        synchronized (this.sections) {
            for (var locals : this.sections.values()) {
                total += locals.length;
            }
        }
        return total;
    }

    public boolean isPersistent() {
        return this.persistent;
    }

    public static short packLocal(int x, int y, int z) {
        return (short) ((x << 8) | (y << 4) | z);
    }

    private static byte[] encode(short[] locals) {
        var buff = ByteBuffer.allocate(2 + locals.length * 2);
        buff.put(FORMAT);
        buff.put((byte) Math.min(locals.length, 255));
        for (short local : locals) {
            buff.putShort(local);
        }
        return buff.array();
    }

    private static short[] decode(byte[] value) {
        if (value == null || value.length < 2 || value[0] != FORMAT) {
            return null;
        }
        int count = value[1] & 0xFF;
        if (value.length < 2 + count * 2) {
            return null;
        }
        var buff = ByteBuffer.wrap(value, 2, count * 2);
        short[] locals = new short[count];
        for (int i = 0; i < count; i++) {
            locals[i] = buff.getShort();
        }
        return locals;
    }

    public interface BeaconConsumer {
        void accept(int x, int y, int z);
    }
}
