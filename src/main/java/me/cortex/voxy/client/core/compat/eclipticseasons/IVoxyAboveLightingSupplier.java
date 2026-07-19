package me.cortex.voxy.client.core.compat.eclipticseasons;

import me.cortex.voxy.common.voxelization.ILightingSupplier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

public record IVoxyAboveLightingSupplier(ILightingSupplier original, DataLayer blockLight, DataLayer skyLight, LevelChunkSection originalSection, LevelChunkSection aboveSection) implements ILightingSupplier
{
    public byte supply(int bx, int by, int bz) {
        if (by < 16) {
            return this.original.supply(bx, by, bz);
        }
        by -= 16;
        int block = 0;
        int sky = 0;
        if (this.blockLight != null) {
            block = this.blockLight.get(bx, by, bz);
        }
        if (this.skyLight != null) {
            sky = this.skyLight.get(bx, by, bz);
        }
        return (byte)(sky | block << 4);
    }

    public BlockState getBlockState(int bx, int by, int bz) {
        if (by < 16) {
            return this.originalSection == null ? Blocks.AIR.defaultBlockState() : this.originalSection.getBlockState(bx, by, bz);
        }
        return this.aboveSection == null ? Blocks.AIR.defaultBlockState() : this.aboveSection.getBlockState(bx, by - 16, bz);
    }
}

