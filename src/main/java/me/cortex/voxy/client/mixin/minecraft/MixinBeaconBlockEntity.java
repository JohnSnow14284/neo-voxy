package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.compat.beacon.DistantBeaconRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BeaconBlockEntity.class)
public abstract class MixinBeaconBlockEntity {
    @Inject(method = "tick", at = @At("TAIL"))
    private static void voxy$captureResolvedBeam(Level level, BlockPos pos, BlockState state,
                                                  BeaconBlockEntity beacon, CallbackInfo ci) {
        DistantBeaconRenderer.onBeaconTick(beacon);
    }
}
