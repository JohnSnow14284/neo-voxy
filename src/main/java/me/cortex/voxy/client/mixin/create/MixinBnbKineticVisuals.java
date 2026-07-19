package me.cortex.voxy.client.mixin.create;

import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import me.cortex.voxy.client.compat.create.KineticCull;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//bits_n_bobs kinetic visuals that override beginFrame - the chain pulley animates its chain links each
//frame, shadowing the base-class cull like Create's own machines do. Same treatment as
//MixinKineticMachineVisuals; @Pseudo because the addon is optional (missing targets skip silently).
@Pseudo
@Mixin(targets = {
        "com.kipti.bnb.content.kinetics.chain_pulley.ChainPulleyVisual"
}, remap = false)
public abstract class MixinBnbKineticVisuals {
    @Unique private boolean voxy$culled;
    @Unique private boolean voxy$enclosed;
    @Unique private long voxy$nextCheckTick;

    @Inject(method = "beginFrame(Ldev/engine_room/flywheel/api/visual/DynamicVisual$Context;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void voxy$cull(DynamicVisual.Context ctx, CallbackInfo ci) {
        BlockPos pos = ((AccessorAbstractBlockEntityVisual) this).voxy$getPos();
        long tick = net.minecraft.client.Minecraft.getInstance().level.getGameTime();
        boolean recheck = tick >= this.voxy$nextCheckTick;
        if (recheck) {
            this.voxy$nextCheckTick = tick + 8 + ((pos.getX() ^ pos.getZ()) & 7);
            this.voxy$enclosed = KineticCull.enclosed(pos);
        }
        boolean beyond = KineticCull.beyond(pos, ctx);
        if (beyond || this.voxy$enclosed) {
            if (!this.voxy$culled) {
                this.voxy$culled = true;
                KineticCull.hide((BlockEntityVisual) this);
                if (beyond && !this.voxy$enclosed) {
                    me.cortex.voxy.client.compat.create.KineticSnapshots.queueCapture(pos);
                }
            } else if (recheck) {
                KineticCull.hide((BlockEntityVisual) this);
            }
            ci.cancel();
        } else if (this.voxy$culled) {
            KineticCull.show((BlockEntityVisual) this);
            this.voxy$culled = false;
            me.cortex.voxy.client.compat.create.KineticSnapshots.queueRemove(pos);
        }
    }
}
