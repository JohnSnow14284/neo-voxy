package me.cortex.voxy.client.core.compat.eclipticseasons;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import me.cortex.voxy.client.core.compat.eclipticseasons.VoxyTool;
import me.cortex.voxy.client.core.compat.eclipticseasons.IVoxyAboveLightingSupplier;
import me.cortex.voxy.client.core.compat.eclipticseasons.VoxyPairCompoundTag;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

public class VoxyImporterTool {
    public static ILightingSupplier getILightingSupplier(Codec<PalettedContainer<BlockState>> blockStateCodec, Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec, PalettedContainerRO<Holder<Biome>> defaultBiomeProvider, PalettedContainer<BlockState> blockContainer, PalettedContainerRO<Holder<Biome>> biomeContainer, ILightingSupplier lightSupplier, CompoundTag above) {
        DataResult blockStatesRes;
        if (!VoxyTool.isVoxyTest()) {
            return lightSupplier;
        }
        if (above == null) {
            return lightSupplier;
        }
        byte[] blockLightData = above.getByteArray("BlockLight");
        byte[] skyLightData = above.getByteArray("SkyLight");
        DataLayer blockLight = blockLightData.length != 0 ? new DataLayer(blockLightData) : null;
        DataLayer skyLight = skyLightData.length != 0 ? new DataLayer(skyLightData) : null;
        LevelChunkSection levelChunkSection = null;
        if (!above.getCompound("block_states").isEmpty() && (blockStatesRes = blockStateCodec.parse((DynamicOps)NbtOps.INSTANCE, (Object)above.getCompound("block_states"))).hasResultOrPartial()) {
            PalettedContainer blockStates = (PalettedContainer)blockStatesRes.getPartialOrThrow();
            PalettedContainerRO<Holder<Biome>> biomes = defaultBiomeProvider;
            CompoundTag optBiomes = above.getCompound("biomes");
            if (!optBiomes.isEmpty()) {
                biomes = biomeCodec.parse(NbtOps.INSTANCE, optBiomes).result().orElse(defaultBiomeProvider);
            }
            levelChunkSection = new LevelChunkSection(blockStates, biomes);
        }
        lightSupplier = new IVoxyAboveLightingSupplier(lightSupplier, blockLight, skyLight, new LevelChunkSection(blockContainer, biomeContainer), levelChunkSection);
        return lightSupplier;
    }

    public static CompoundTag getVoxyPairCompoundTag(int y, CompoundTag compoundTag, CompoundTag chunk) {
        if (!VoxyTool.isVoxyTest()) {
            return compoundTag;
        }
        ListTag sections = chunk.getList("sections", 10);
        int index = y - sections.getCompound(0).getInt("Y") + 1;
        return new VoxyPairCompoundTag().setOriginal(compoundTag).setAbove(index < sections.size() ? sections.getCompound(index) : null);
    }
}

