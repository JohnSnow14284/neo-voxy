package me.cortex.voxy.client.mixin.eclipticseasons;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import me.cortex.voxy.client.core.compat.eclipticseasons.VoxyTool;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.world.other.Mapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value={WorldConversionFactory.class})
public abstract class MixinWorldConversionFactory {
    @WrapOperation(remap=false, method={"convert(Lme/cortex/voxy/common/voxelization/VoxelizedSection;Lme/cortex/voxy/common/world/other/Mapper;Lnet/minecraft/world/level/chunk/PalettedContainer;Lnet/minecraft/world/level/chunk/PalettedContainerRO;Lme/cortex/voxy/common/voxelization/ILightingSupplier;ZJ)Lme/cortex/voxy/common/voxelization/VoxelizedSection;"}, at={@At(value="INVOKE", target="Lme/cortex/voxy/common/world/other/Mapper;composeMappingId(BII)J")})
    private static long eclipticseasons$convert(byte light, int blockId, int biomeId, Operation<Long> original, @Local(argsOnly=true) Mapper mapper, @Local(argsOnly=true) ILightingSupplier lightSupplier, @Local(name={"i"}) int i, @Local(argsOnly=true) VoxelizedSection section) {
        blockId = VoxyTool.changeBlockId(blockId, mapper, i, section, lightSupplier, biomeId);
        return (Long)original.call(new Object[]{light, blockId, biomeId});
    }
}

