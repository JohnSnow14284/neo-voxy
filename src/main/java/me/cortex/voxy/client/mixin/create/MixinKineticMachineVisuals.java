package me.cortex.voxy.client.mixin.create;

import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import me.cortex.voxy.client.compat.create.KineticCull;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//The kinetic machines that override beginFrame to animate a moving sub-model each frame - the press
//head, mixer pole, deployer hand, arm segments, cranks and valves, the flywheel wheel, the steam-engine
//piston, the chain drive, and the bearing/pulley/gantry/elevator/valve/ejector shafts. Their override
//shadows the base-class beginFrame, so the same distance cull is prepended to each: beyond the render
//distance hide the instances and skip the per-frame transform, on return reveal and let it animate
//again. All sixteen reach AbstractBlockEntityVisual (via ShaftVisual/KineticBlockEntityVisual or
//directly), so `pos` and collectCrumblingInstances resolve on every one; six of them sit outside
//content/kinetics/ (bearing, pulley, gantry, elevator, fluid valve, ejector) yet are placed machines.
//
//The *ActorVisual machines (saw/deployer/drill/harvester/roller/PSI/stabilized bearing) are not here:
//they extend ActorVisual, are contraption actors rather than placed blocks, and carry no `pos` - the
//contraption cull path owns them.
@Mixin({
        com.simibubi.create.content.kinetics.press.PressVisual.class,
        com.simibubi.create.content.kinetics.gauge.GaugeVisual.class,
        com.simibubi.create.content.kinetics.mechanicalArm.ArmVisual.class,
        com.simibubi.create.content.kinetics.deployer.DeployerVisual.class,
        com.simibubi.create.content.kinetics.crank.ValveHandleVisual.class,
        com.simibubi.create.content.kinetics.crank.HandCrankVisual.class,
        com.simibubi.create.content.kinetics.flywheel.FlywheelVisual.class,
        com.simibubi.create.content.kinetics.steamEngine.SteamEngineVisual.class,
        com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorVisual.class,
        com.simibubi.create.content.kinetics.mixer.MixerVisual.class,
        //Placed kinetic machines that live outside content/kinetics/ (rotating shafts, same treatment):
        com.simibubi.create.content.contraptions.bearing.BearingVisual.class,
        com.simibubi.create.content.contraptions.pulley.AbstractPulleyVisual.class,
        com.simibubi.create.content.contraptions.gantry.GantryCarriageVisual.class,
        com.simibubi.create.content.contraptions.elevator.ElevatorPulleyVisual.class,
        com.simibubi.create.content.fluids.pipes.valve.FluidValveVisual.class,
        com.simibubi.create.content.logistics.depot.EjectorVisual.class
})
public abstract class MixinKineticMachineVisuals {
    @Unique private boolean voxy$culled;

    //Throttled enclosure verdict, staggered by position, on game time (see MixinKineticBlockEntityVisual)
    @Unique private boolean voxy$enclosed;
    @Unique private long voxy$nextCheckTick;

    @Inject(method = "beginFrame(Ldev/engine_room/flywheel/api/visual/DynamicVisual$Context;)V",
            at = @At("HEAD"), cancellable = true)
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
                //An enclosed part is invisible and needs no distant copy
                if (beyond && !this.voxy$enclosed) {
                    me.cortex.voxy.client.compat.create.KineticSnapshots.queueCapture(pos);
                }
            } else if (recheck) {
                //Re-hide on the throttle beat only (block updates rebuild instances visible)
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
