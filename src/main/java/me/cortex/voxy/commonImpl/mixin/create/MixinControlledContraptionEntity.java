package me.cortex.voxy.commonImpl.mixin.create;

import me.cortex.voxy.compat.create.CreateContraptionControllerAccessor;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.contraptions.ControlledContraptionEntity", remap = false)
public abstract class MixinControlledContraptionEntity implements CreateContraptionControllerAccessor {
    @Shadow(remap = false) protected BlockPos controllerPos;

    @Override
    public BlockPos voxy$getControllerPos() {
        return this.controllerPos;
    }
}
