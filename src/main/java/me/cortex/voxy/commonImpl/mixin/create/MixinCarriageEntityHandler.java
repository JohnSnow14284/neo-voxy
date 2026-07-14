package me.cortex.voxy.commonImpl.mixin.create;

import me.cortex.voxy.compat.create.CreateFarEntityCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.trains.entity.CarriageEntityHandler", remap = false)
public abstract class MixinCarriageEntityHandler {
    @Inject(method = "isActiveChunk", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void voxy$keepFarTrainEntityActive(Level level, BlockPos position,
                                                       CallbackInfoReturnable<Boolean> cir) {
        if (CreateFarEntityCompat.isTrainRenderActive(level, position)) {
            cir.setReturnValue(true);
        }
    }
}
