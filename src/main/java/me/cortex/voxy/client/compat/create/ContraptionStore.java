package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ShapeBlock;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

//Persists frozen contraption snapshots into voxy's aux storage, so a structure the player left behind is
//still standing in the LOD after a reload rather than reappearing only once they walk back to it.
//
//What is stored is the source, not the mesh: a block list plus the pose it froze at. The mesh is rebuilt
//from that, which is also what lets a snapshot be evicted from memory and brought back - the reason the
//resident set could only grow before.
//
//Block states go out as a palette of BlockState NBT rather than as voxy block ids. Those ids belong to
//one Mapper over a store that is explicitly a deletable cache, so deleting it renumbers everything and
//an id list held anywhere else silently decodes to different blocks - the id space is dense, so there is
//no invalid value to detect it by. The NBT form carries the block's name and properties and goes through
//the vanilla data fixer on a rename, which is what makes it safe to keep outside the store that wrote it.
public final class ContraptionStore {
    public static final String TABLE = "create_contraptions";
    private static final byte FORMAT = 1;
    //A contraption is bounded by the +-127 local coordinate packing, so its block count cannot approach
    //this; the cap only stops a corrupt length from allocating wildly
    private static final int MAX_BLOCKS = 1 << 20;

    private ContraptionStore() {}

    public record Stored(UUID id, DistantContraptionManager.Source source,
                         Matrix4f pose, double x, double y, double z, ResourceLocation dim) {}

    //UUIDs do not fit a long key, so the two halves are mixed. A collision would show one contraption in
    //place of another, which is why the record carries its own id and the loader checks it.
    private static long keyOf(UUID id) {
        return id.getMostSignificantBits() * 31L + id.getLeastSignificantBits();
    }

    public static void save(SectionStorage storage, UUID id, DistantContraptionManager.Snapshot snap) {
        if (!storage.supportsAuxTable(TABLE) || snap.source() == null || snap.dim() == null) {
            return;
        }
        try {
            storage.putAux(TABLE, keyOf(id), encode(id, snap));
        } catch (Throwable t) {
            Logger.error("Storing contraption snapshot " + id, t);
        }
    }

    public static void remove(SectionStorage storage, UUID id) {
        if (storage.supportsAuxTable(TABLE)) {
            storage.deleteAux(TABLE, keyOf(id));
        }
    }

    public static List<Stored> loadAll(SectionStorage storage) {
        var out = new ArrayList<Stored>();
        if (!storage.supportsAuxTable(TABLE)) {
            return out;
        }
        try {
            storage.forEachAux(TABLE, (key, value) -> {
                var stored = decode(value);
                if (stored != null) {
                    out.add(stored);
                }
            });
        } catch (Throwable t) {
            Logger.error("Reading stored contraption snapshots; continuing without them", t);
            out.clear();
        }
        return out;
    }

    private static byte[] encode(UUID id, DistantContraptionManager.Snapshot snap) throws Exception {
        var blocks = snap.source().blocks();
        //One entry per distinct state; a contraption of five hundred blocks is usually a few dozen
        var paletteIndex = new HashMap<BlockState, Integer>();
        var palette = new ArrayList<BlockState>();
        for (var block : blocks) {
            paletteIndex.computeIfAbsent(block.state(), s -> {
                palette.add(s);
                return palette.size() - 1;
            });
        }

        var bytes = new ByteArrayOutputStream(blocks.size() * 4 + 512);
        var out = new DataOutputStream(bytes);
        out.writeByte(FORMAT);
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
        out.writeDouble(snap.x());
        out.writeDouble(snap.y());
        out.writeDouble(snap.z());
        out.writeUTF(snap.dim().toString());
        float[] pose = new float[16];
        snap.local().get(pose);
        for (float f : pose) {
            out.writeFloat(f);
        }

        var root = new CompoundTag();
        var paletteTag = new ListTag();
        for (var state : palette) {
            paletteTag.add(BlockState.CODEC.encodeStart(NbtOps.INSTANCE, state)
                    .getOrThrow(e -> new IllegalStateException("Encoding block state: " + e)));
        }
        root.put("palette", paletteTag);
        var paletteBytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, paletteBytes);
        out.writeInt(paletteBytes.size());
        out.write(paletteBytes.toByteArray());

        out.writeInt(blocks.size());
        boolean wide = palette.size() > 255;
        for (var block : blocks) {
            out.writeByte(block.x());
            out.writeByte(block.y());
            out.writeByte(block.z());
            int idx = paletteIndex.get(block.state());
            if (wide) {
                out.writeShort(idx);
            } else {
                out.writeByte(idx);
            }
        }
        out.flush();
        return bytes.toByteArray();
    }

    private static Stored decode(byte[] value) {
        if (value == null || value.length < 2 || value[0] != FORMAT) {
            return null;
        }
        try (var in = new DataInputStream(new ByteArrayInputStream(value, 1, value.length - 1))) {
            var id = new UUID(in.readLong(), in.readLong());
            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
            var dim = ResourceLocation.parse(in.readUTF());
            var pose = new Matrix4f();
            float[] raw = new float[16];
            for (int i = 0; i < 16; i++) {
                raw[i] = in.readFloat();
            }
            pose.set(raw);

            int paletteBytes = in.readInt();
            if (paletteBytes < 0 || paletteBytes > value.length) {
                return null;
            }
            byte[] paletteRaw = new byte[paletteBytes];
            in.readFully(paletteRaw);
            var root = NbtIo.readCompressed(new ByteArrayInputStream(paletteRaw), NbtAccounter.unlimitedHeap());
            var paletteTag = root.getList("palette", 10);
            var palette = new ArrayList<BlockState>(paletteTag.size());
            for (int i = 0; i < paletteTag.size(); i++) {
                //A block whose mod is gone decodes to nothing; it drops out of the shape rather than
                //taking the whole snapshot with it
                palette.add(BlockState.CODEC.parse(NbtOps.INSTANCE, paletteTag.get(i)).result().orElse(null));
            }

            int count = in.readInt();
            if (count < 0 || count > MAX_BLOCKS) {
                return null;
            }
            boolean wide = palette.size() > 255;
            var blocks = new ArrayList<ShapeBlock>(count);
            for (int i = 0; i < count; i++) {
                byte bx = in.readByte(), by = in.readByte(), bz = in.readByte();
                int idx = wide ? in.readUnsignedShort() : in.readUnsignedByte();
                if (idx >= palette.size()) {
                    return null;
                }
                var state = palette.get(idx);
                if (state != null) {
                    blocks.add(new ShapeBlock(bx, by, bz, state));
                }
            }
            if (blocks.isEmpty()) {
                return null;
            }
            //Copycat model data is not stored: it is read from block entity nbt that left with the
            //entity, so a reloaded snapshot shows the copycat's own model rather than what it was
            //wearing. Everything else about the shape is intact.
            return new Stored(id, new DistantContraptionManager.Source(blocks, null), pose, x, y, z, dim);
        } catch (Throwable t) {
            Logger.error("Decoding a stored contraption snapshot; dropping it", t);
            return null;
        }
    }
}
