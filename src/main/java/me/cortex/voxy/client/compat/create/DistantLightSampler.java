package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.world.level.Level;

//Samples light from voxy's own voxel store - every LOD voxel carries sky+block light, making the
//store a ready-made light cache for places far outside loaded chunks. Samples the voxel above the
//given position (the air that carries the ambient light), walking up mip levels until data exists.
public final class DistantLightSampler {
    public static final int FALLBACK = 15; //plain full skylight, block light 0

    private DistantLightSampler() {}

    //Returns packed voxel light (block << 4 | sky), or FALLBACK when no data is stored
    public static int sample(Level level, int x, int y, int z) {
        try {
            var engine = WorldIdentifier.ofEngineNullable(level);
            if (engine == null) {
                return FALLBACK;
            }
            int ay = y + 1;
            for (int lvl = 0; lvl <= 4; lvl++) {
                var section = engine.acquireIfExists(lvl, x >> (5 + lvl), ay >> (5 + lvl), z >> (5 + lvl));
                if (section == null) {
                    continue;
                }
                try {
                    int lx = (x >> lvl) & 31, ly = (ay >> lvl) & 31, lz = (z >> lvl) & 31;
                    //get() never materialises. Note the uniform value may legitimately be 0, which the
                    //check below treats as "no data, try a coarser level" - so it must be returned as-is.
                    long voxel = section.get(lx | (lz << 5) | (ly << 10));
                    if (voxel == 0) {
                        continue; //void, try a coarser level
                    }
                    return Mapper.getLightId(voxel);
                } finally {
                    section.release();
                }
            }
        } catch (Throwable ignored) {
        }
        return FALLBACK;
    }

    public static int sky(int packed) {
        return packed & 0xF;
    }

    public static int block(int packed) {
        return (packed >> 4) & 0xF;
    }
}
