package me.cortex.voxy.client.mixin.eclipticseasons;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.serialization.Codec;
import me.cortex.voxy.client.core.compat.eclipticseasons.VoxyImporterTool;
import me.cortex.voxy.client.core.compat.eclipticseasons.VoxyTool;
import me.cortex.voxy.client.core.compat.eclipticseasons.VoxyPairCompoundTag;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={WorldImporter.class})
public class MixinWorldImporter {
    @Shadow(remap=false)
    @Final
    private Codec<PalettedContainer<BlockState>> blockStateCodec;
    @Shadow(remap=false)
    @Final
    private PalettedContainerRO<Holder<Biome>> defaultBiomeProvider;
    @Shadow(remap=false)
    @Final
    private Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;

    @WrapOperation(remap=false, method={"importChunkNBT"}, at={@At(value="INVOKE", target="Lme/cortex/voxy/commonImpl/importers/WorldImporter;importSectionNBT(IIILnet/minecraft/nbt/CompoundTag;)V")})
    private void eclipticseasons$importChunkNBT(WorldImporter instance, int x, int y, int z, CompoundTag compoundTag, Operation<Void> original, @Local(argsOnly=true) CompoundTag chunk) {
        original.call(new Object[]{instance, x, y, z, VoxyImporterTool.getVoxyPairCompoundTag(y, compoundTag, chunk)});
    }

    @Inject(remap=false, method={"importSectionNBT"}, at={@At(value="HEAD")})
    private void eclipticseasons$importSectionNBT_init(int x, int y, int z, CompoundTag section, CallbackInfo ci, @Local(argsOnly=true) LocalRef<CompoundTag> sectionRef, @Share(value="above") LocalRef<CompoundTag> aboveRef) {
        if (VoxyTool.isVoxyTest() && section instanceof VoxyPairCompoundTag) {
            VoxyPairCompoundTag voxyPairCompoundTag = (VoxyPairCompoundTag)section;
            sectionRef.set(voxyPairCompoundTag.getOriginal());
            aboveRef.set(voxyPairCompoundTag.getAbove());
        }
    }

    @WrapOperation(method={"importSectionNBT"}, at={@At(value="INVOKE", target="Lme/cortex/voxy/common/voxelization/WorldConversionFactory;convert(Lme/cortex/voxy/common/voxelization/VoxelizedSection;Lme/cortex/voxy/common/world/other/Mapper;Lnet/minecraft/world/level/chunk/PalettedContainer;Lnet/minecraft/world/level/chunk/PalettedContainerRO;Lme/cortex/voxy/common/voxelization/ILightingSupplier;)Lme/cortex/voxy/common/voxelization/VoxelizedSection;")})
    private VoxelizedSection eclipticseasons$importSectionNBT_set(VoxelizedSection section, Mapper stateMapper, PalettedContainer<BlockState> blockContainer, PalettedContainerRO<Holder<Biome>> biomeContainer, ILightingSupplier lightSupplier, Operation<VoxelizedSection> original, @Share(value="above") LocalRef<CompoundTag> aboveRef) {
        lightSupplier = VoxyImporterTool.getILightingSupplier(this.blockStateCodec, this.biomeCodec, this.defaultBiomeProvider, blockContainer, biomeContainer, lightSupplier, (CompoundTag)aboveRef.get());
        return (VoxelizedSection)original.call(new Object[]{section, stateMapper, blockContainer, biomeContainer, lightSupplier});
    }
}

