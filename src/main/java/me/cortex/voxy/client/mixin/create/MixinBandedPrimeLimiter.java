package me.cortex.voxy.client.mixin.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//Flywheel's default update limiter slows dynamic visuals down by camera distance - and a ship-borne
//visual's distance is measured to its plot-grid storage position (~2e7 blocks), parking everything on
//a ship in the slowest update band no matter how close the ship itself is. The method only receives
//the distance, but that distance is the ship signature: nothing real is 3e6+ blocks out and still
//visualized, so anything past that updates at full rate. World visuals keep their normal banding.
@Mixin(targets = "dev.engine_room.flywheel.impl.visual.BandedPrimeLimiter", remap = false)
public class MixinBandedPrimeLimiter {
    @Inject(method = "shouldUpdate", at = @At("HEAD"), cancellable = true)
    private void voxy$fullRateOnShips(double distanceSquared, CallbackInfoReturnable<Boolean> cir) {
        if (distanceSquared > 1.0e13) {
            cir.setReturnValue(true);
        }
    }
}
