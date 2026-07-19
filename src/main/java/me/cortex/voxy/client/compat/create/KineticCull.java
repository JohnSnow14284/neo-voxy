package me.cortex.voxy.client.compat.create;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import me.cortex.voxy.client.compat.ShipBorne;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

//Shared distance-cull for Create's placed kinetic block-entity visuals (shafts, cogs, gearboxes, fans,
//belts, waterwheels and the machines: press/mixer/deployer/arm/...). Their moving parts are Flywheel
//RotatingInstances with no distance limit of their own: once the block's chunk is client-loaded they
//submit as GPU instances at full detail, and under iris+colorwheel (Flywheel forced on) that is the
//only draw path. EntityCulling (nowheel) only occlusion-culls them, which fails over voxy LOD where
//there is no real block to occlude - so a spinning shaft floats past the render distance on top of the
//LOD. Chunks load in a horizontal cylinder (full height) while rendering culls to a sphere, so the
//worst case is a machine straight down a deep mine: still loaded, still animating, but well past the
//render sphere.
//
//We hide every instance a visual owns beyond the effective render distance (3D spherical, honoring
//height) and reveal them on return, cutting the moving part exactly where the static body drops to LOD.
//collectCrumblingInstances enumerates a visual's drawable instances without allocating, so it is the one
//type-agnostic handle to reach them all whatever the machine. setVisible drops the instance from the
//instancer's draw list and is fully reversible - the RotatingInstance keeps its axis/speed/offset.
public final class KineticCull {
    private KineticCull() {}

    //Constant consumers so the per-frame collectCrumblingInstances walk never allocates.
    private static final Consumer<Instance> HIDE = instance -> {
        if (instance != null) {
            instance.setVisible(false);
        }
    };
    private static final Consumer<Instance> SHOW = instance -> {
        if (instance != null) {
            instance.setVisible(true);
            //A block update may have re-pushed rotation params while the instance was hidden; mark it so
            //the reveal reuploads the current state.
            instance.setChanged();
        }
    };

    //Cut exactly at the effective render distance (already min of client/server, spherical), where the
    //vanilla block mesh hands over to the LOD copy. No margin - the moving part should vanish precisely
    //as its static body becomes LOD.
    private static double reachSq() {
        double reach = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        return reach * reach;
    }

    private static boolean beyond(BlockPos pos, double camX, double camY, double camZ) {
        double dx = pos.getX() + 0.5 - camX;
        double dy = pos.getY() + 0.5 - camY;
        double dz = pos.getZ() + 0.5 - camZ;
        return (dx * dx + dy * dy + dz * dz) > reachSq();
    }

    //Flywheel visual path: the camera comes from the frame context.
    public static boolean beyond(BlockPos pos, DynamicVisual.Context ctx) {
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantKinetics) {
            return false;
        }
        //Ship-borne machines render natively, uncut: a ship is one connected drivetrain, and any
        //snapshot/recapture scheme desynchronises adjacent shafts. Ships are few, so the full
        //Flywheel render is cheap; LOD-depth occlusion is the vanilla-depth writeback's concern.
        if (ShipBorne.isShipBorne(pos)) {
            return false;
        }
        Vec3 cam = ctx.camera().getPosition();
        return beyond(pos, cam.x, cam.y, cam.z);
    }

    //Vanilla-BER fallback path (Flywheel backend off): the camera comes from the game renderer.
    public static boolean beyondForRender(BlockPos pos) {
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantKinetics || ShipBorne.isShipBorne(pos)) {
            return false;
        }
        var mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) {
            return false;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        return beyond(pos, cam.x, cam.y, cam.z);
    }

    public static void hide(BlockEntityVisual visual) {
        visual.collectCrumblingInstances(HIDE);
        AzimuthBehaviourIndex.apply(visual, HIDE);
    }

    public static void show(BlockEntityVisual visual) {
        visual.collectCrumblingInstances(SHOW);
        AzimuthBehaviourIndex.apply(visual, SHOW);
    }

    //Provably-invisible moving parts: a kinetic block whose open faces are all covered by full opaque
    //cubes can never show its rotating instance, yet it keeps submitting to the GPU forever - and the
    //raycast culler (nowheel/EntityCulling) structurally cannot catch this, because its ray target is
    //the 3x3x3 shell around the BE: the casing and the face-neighbours are part of the target, never
    //occluders. Encased SHAFTS only expose their rod along the rotation axis, so two opaque axis ends
    //suffice; anything else needs all six. Not gated on the voxy render state - an invisible instance
    //is pure waste with or without LOD - only on its own config switch. Ship-borne positions are left
    //alone like the distance cull.
    //
    //Cogwheels are excluded: their teeth stick out on the faces perpendicular to the axis (that is how
    //they mesh), so the axis-end rule is wrong for them - an encased cog with both axis ends covered
    //still shows its rim. The six-face rule would hold for a fully buried cog, but this cull is for
    //shafts.
    public static boolean enclosed(BlockPos pos) {
        if (!VoxyConfig.CONFIG.kineticEnclosedCulling) {
            return false;
        }
        var level = Minecraft.getInstance().level;
        if (level == null || me.cortex.voxy.client.compat.ShipBorne.isShipBorne(pos)) {
            return false;
        }
        var state = level.getBlockState(pos);
        var block = state.getBlock();
        if (block instanceof com.simibubi.create.content.kinetics.simpleRelays.ICogWheel) {
            return false;
        }
        if (block instanceof com.simibubi.create.content.decoration.encasing.EncasedBlock
                && block instanceof com.simibubi.create.content.kinetics.base.IRotate rotate) {
            var axis = rotate.getRotationAxis(state);
            return opaqueNeighbor(level, pos.relative(net.minecraft.core.Direction.get(
                            net.minecraft.core.Direction.AxisDirection.POSITIVE, axis)))
                    && opaqueNeighbor(level, pos.relative(net.minecraft.core.Direction.get(
                            net.minecraft.core.Direction.AxisDirection.NEGATIVE, axis)));
        }
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            if (!opaqueNeighbor(level, pos.relative(direction))) {
                return false;
            }
        }
        return true;
    }

    private static boolean opaqueNeighbor(net.minecraft.world.level.Level level, BlockPos pos) {
        return level.getBlockState(pos).isSolidRender(level, pos);
    }
}
