package me.cortex.voxy.commonImpl.mixin.sable;

import me.cortex.voxy.commonImpl.compat.sable.SableContraptionRenderDistance;
import me.cortex.voxy.commonImpl.compat.sable.SableParentChunkLightSync;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem")
public class MixinSubLevelTrackingSystem {
    @Shadow(remap = false)
    @Final
    private ServerLevel level;

    @Inject(method = "shouldLoad", at = @At("HEAD"), cancellable = true, remap = false)
    private void voxy$useVoxySimulatedContraptionRenderDistance(Player player, Vector3dc position, CallbackInfoReturnable<Boolean> cir) {
        double rangeBlocks = SableContraptionRenderDistance.getRangeBlocks(this.level);
        if (rangeBlocks <= 0.0) {
            return;
        }

        double dx = position.x() - player.getX();
        double dz = position.z() - player.getZ();
        cir.setReturnValue((dx * dx) + (dz * dz) < rangeBlocks * rangeBlocks);
    }

    @Inject(method = "tick(Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;)V", at = @At("TAIL"), remap = false)
    private void voxy$syncParentChunkSkyLight(CallbackInfo ci) {
        SableParentChunkLightSync.tick(this.level);
    }
}
