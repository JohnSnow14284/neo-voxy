package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import me.cortex.voxy.client.compat.create.KineticCull;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

//The kinetic visual family that does not override beginFrame - the shaft/cogwheel/gearbox/belt/fan/
//waterwheel/saw majority that a base is wired from. These are SimpleTickableVisuals: tick pushes the
//rotation params, the GPU spins the RotatingInstance, and there is no per-frame callback to hang a
//distance check on. Making the shared base a SimpleDynamicVisual gets Flywheel to call beginFrame each
//frame for every one of them (Storage.setup enrols on `instanceof SimpleDynamicVisual`, inherited here;
//the default engine and colorwheel's ClrwlEngine both honour it), so the moving instances can be hidden
//beyond the render distance and revealed on return.
//
//Machines that override beginFrame (press/mixer/deployer/arm/...) would shadow this one, so they are
//culled by MixinKineticMachineVisuals instead; the vanilla-BER fallback by MixinSafeBlockEntityRenderer.
@Mixin(KineticBlockEntityVisual.class)
public abstract class MixinKineticBlockEntityVisual implements SimpleDynamicVisual {
    @Unique private boolean voxy$culled;

    //A fresh visual means the block just (re)loaded or updated at this position: whatever frozen
    //snapshot exists there is stale - the live path (or a new capture on the next cull) owns it now.
    @org.spongepowered.asm.mixin.injection.Inject(method = "<init>", at = @org.spongepowered.asm.mixin.injection.At("TAIL"))
    private void voxy$dropStaleSnapshot(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        me.cortex.voxy.client.compat.create.KineticSnapshots.queueRemove(
                ((AccessorAbstractBlockEntityVisual) this).voxy$getPos());
    }

    //Throttled enclosure verdict: 6 block lookups are too dear per visual per frame, and neighbours
    //rarely change - re-evaluate every ~8 ticks, staggered by position so a base's visuals do not all
    //re-check on the same frame. Game time, not wall clock: a per-visual-per-frame currentTimeMillis
    //added up across a large base.
    @Unique private boolean voxy$enclosed;
    @Unique private long voxy$nextCheckTick;

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
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
                //Crossing out of the live path: freeze the moving part for the distant copy. An
                //enclosed part is invisible and needs no copy.
                if (beyond && !this.voxy$enclosed) {
                    me.cortex.voxy.client.compat.create.KineticSnapshots.queueCapture(pos);
                }
            } else if (recheck) {
                //Block updates rebuild instances visible; re-hide on the throttle beat rather than
                //walking every instance of every culled visual every frame
                KineticCull.hide((BlockEntityVisual) this);
            }
        } else if (this.voxy$culled) {
            KineticCull.show((BlockEntityVisual) this);
            this.voxy$culled = false;
            me.cortex.voxy.client.compat.create.KineticSnapshots.queueRemove(pos);
        }
    }
}
