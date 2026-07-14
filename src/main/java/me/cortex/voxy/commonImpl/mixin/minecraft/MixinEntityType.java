package me.cortex.voxy.commonImpl.mixin.minecraft;

import me.cortex.voxy.compat.create.CreateFarEntityCompat;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityType.class)
public abstract class MixinEntityType {
    @Inject(method = "clientTrackingRange", at = @At("RETURN"), cancellable = true)
    private void voxy$extendCreateTrackingRange(CallbackInfoReturnable<Integer> cir) {
        EntityType<?> type = (EntityType<?>) (Object) this;
        if (CreateFarEntityCompat.shouldExtendServerTracking(type)) {
            cir.setReturnValue(Math.max(cir.getReturnValue(), CreateFarEntityCompat.TRACKING_DISTANCE_CHUNKS));
        }
    }
}
