package me.cortex.voxy.client.compat.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ShapeBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.RenderShape;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

//Client-side "leave-behind snapshot" store for distant Create contraptions (bearings/windmills,
//pistons, gantries, mounted/minecart contraptions). Unlike trains - which the server streams because
//they run in unloaded chunks - a contraption is an entity the player necessarily walked past, so the
//data is already client-side. While a contraption sits inside the render distance we keep its baked
//block mesh and its live world transform up to date; once it crosses out (or its chunk unloads) the
//snapshot freezes and DistantContraptionRenderer draws it statically, holding the pose/rotation it
//had when the player left. Pure client, no server sampling, no protocol.
public final class DistantContraptionManager {
    private DistantContraptionManager() {}

    //Coordinate range of the per-carriage byte packing reused from the train path
    private static final int MAX_LOCAL = 127;

    public static final class Snapshot {
        CarriageMeshBaker.BakedCarriage mesh;
        //M_local from AbstractContraptionEntity.applyLocalTransforms; the world position is kept
        //separately as doubles so the draw can be camera-relative without float world-coord error.
        final Matrix4f local = new Matrix4f();
        double x, y, z;
        ResourceLocation dim;
        int lightPacked = -1;
        long lastSeenMs;
        boolean baked;
        //Set once a bake ran on a non-empty contraption but produced no drawable mesh (all non-MODEL
        //blocks); stops the per-tick 64KB re-bake retry for structures that can never draw.
        boolean bakeGaveNothing;
        //Bearing/piston-driven: pose froze at the reach boundary (one refresh on the crossing tick)
        boolean frozenControlled;
        //The entity appeared in entitiesForRendering this tick. The renderer only yields to the live
        //entity when this is true: entity tracking ends well INSIDE the render distance, so a pure
        //distance handover left a ring where neither side drew (walking closer made the structure
        //vanish until the tracker picked it up).
        volatile boolean live;

        public Matrix4f local() { return this.local; }
        public boolean live() { return this.live; }
        public double x() { return this.x; }
        public double y() { return this.y; }
        public double z() { return this.z; }
        public ResourceLocation dim() { return this.dim; }
        public int lightPacked() { return this.lightPacked; }
        public CarriageMeshBaker.BakedCarriage mesh() { return this.mesh; }
    }

    private static final Map<UUID, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final PoseStack SCRATCH_POSE = new PoseStack();

    //Diagnostics for /voxy debug trains
    public static volatile int snapshotCount;

    //Refresh the snapshot of every loaded contraption within the LOD radius, whatever its distance.
    //Chunks load in a horizontal cylinder (full world height) while rendering culls to a sphere, so a
    //contraption straight down a deep mine is still LOADED and its motion is live even though it is
    //past the render distance - that one keeps animating in the LOD. A contraption whose chunk unloads
    //(the player walked away horizontally) simply drops out of entitiesForRendering, so its snapshot
    //stops refreshing and freezes at the last pose. Only bounded by the LOD radius (past it we never
    //draw). Runs on the client tick - applyLocalTransforms only reads entity state, no render context.
    //Did this controlled contraption's bearing/piston block just self-recapture its kinetic snapshot?
    //Consuming the notification re-freezes the structure pose on the same tick (drivetrain alignment).
    private static boolean anchorRecaptured(AbstractContraptionEntity ce) {
        var anchor = ((me.cortex.voxy.client.mixin.create.AccessorControlledContraptionEntity) ce).voxy$getControllerPos();
        return anchor != null && KineticSnapshots.consumeAnchorRecapture(anchor);
    }

    public static void update(ClientLevel level, double camX, double camY, double camZ, double maxDist) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled() || !VoxyConfig.CONFIG.distantContraptions) {
            if (!SNAPSHOTS.isEmpty()) {
                clearAll();
            }
            return;
        }
        long now = System.currentTimeMillis();
        double maxDistSq = maxDist * maxDist;
        var dimId = level.dimension().location();

        var seenThisTick = new java.util.HashSet<UUID>();
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof AbstractContraptionEntity ce)) {
                continue;
            }
            seenThisTick.add(ce.getUUID());
            //Trains (CarriageContraptionEntity, a subclass of OrientedContraptionEntity) have their own
            //dedicated remote-LOD path - DistantTrainRenderer + the server-side CreateTrainSampler that
            //streams their poses even through unloaded chunks. Snapshotting them here too would double-
            //draw and leave a frozen ghost where a train drove past. Non-train contraptions
            //(bearings/gantries/pistons/minecart-mounted OrientedContraptionEntity) still belong here.
            if (ce instanceof CarriageContraptionEntity) {
                continue;
            }
            //A contraption riding a sable ship is stored at plot-grid coordinates and only moved onto the
            //ship at render time, so a snapshot of it would be drawn ~2e7 blocks out. Sable renders it.
            if (me.cortex.voxy.client.compat.ShipBorne.isShipBorne(ce.getX(), ce.getZ())) {
                continue;
            }
            double dx = ce.getX() - camX, dy = ce.getY() - camY, dz = ce.getZ() - camZ;
            if (dx * dx + dy * dy + dz * dz > maxDistSq) {
                //Past the LOD radius entirely: never drawn, no reason to refresh
                continue;
            }
            Contraption contraption = ce.getContraption();
            if (contraption == null) {
                continue;
            }
            var snap = SNAPSHOTS.computeIfAbsent(ce.getUUID(), k -> new Snapshot());
            //Live = present AND actually drawing: an entity EntityCulling has occlusion-culled renders
            //nothing, and yielding to it blinks the structure out whenever the ray test flips
            snap.live = !NowheelCulled.isCulled(ce);
            if (snap.mesh == null && !snap.bakeGaveNothing) {
                //A contraption first seen from afar often has no block data yet (the NBT arrives after
                //the entity), so keep retrying while it is empty. But once it has blocks and the bake
                //still produced no mesh (a structure of purely non-MODEL blocks), stop - re-baking a
                //64KB native buffer every tick forever for a snapshot that can never draw was pure waste.
                if (!contraption.getBlocks().isEmpty()) {
                    snap.mesh = bakeContraption(contraption);
                    snap.baked = snap.mesh != null;
                    snap.bakeGaveNothing = snap.mesh == null;
                }
            } else if (snap.bakeGaveNothing) {
                me.cortex.voxy.commonImpl.PerfStats.contraptionRebakeSkipped.increment();
            }
            if (snap.mesh == null) {
                continue;
            }
            //Bearing/piston/gantry-driven contraptions freeze at the reach boundary rather than
            //staying live: their controller block's moving parts (the bearing's top disc) are frozen
            //there by the kinetic snapshot, and a disc stopped mid-spin under a still-rotating sail
            //reads as misalignment. Freezing both halves at the same boundary keeps them meshed.
            //lastSeen still refreshes so the frozen snapshot is not evicted while loaded.
            if (ce instanceof com.simibubi.create.content.contraptions.ControlledContraptionEntity
                    && snap.lightPacked >= 0) {
                var mc = net.minecraft.client.Minecraft.getInstance();
                double reach = mc.options.getEffectiveRenderDistance() * 16.0;
                double camDx = ce.getX() - camX, camDy = ce.getY() - camY, camDz = ce.getZ() - camZ;
                if (camDx * camDx + camDy * camDy + camDz * camDz > reach * reach) {
                    //One last full refresh ON the crossing tick, then freeze: without it the frozen
                    //pose is the tick before the boundary while the bearing disc snapshot captures the
                    //tick after - at high rpm that couple of degrees reads as the halves misaligning.
                    if (!snap.frozenControlled) {
                        snap.frozenControlled = true;
                        //Freeze tick: recapture the controller's kinetic snapshot now, so the bearing
                        //disc and the structure hold the same tick's angle - each side freezing on
                        //whichever tick it happened to cross the boundary was the residual mesh offset
                        var anchor = ((me.cortex.voxy.client.mixin.create.AccessorControlledContraptionEntity) ce).voxy$getControllerPos();
                        if (anchor != null) {
                            KineticSnapshots.recaptureAt(anchor);
                        }
                    } else if (anchorRecaptured(ce)) {
                        //The bearing disc just recaptured on its own (its boundary crossing, or the
                        //sweep): fall through to the full pose refresh below so both halves re-freeze
                        //on this same tick
                    } else {
                        //Settle-follow: a frozen structure that keeps spinning stays frozen, but when
                        //it decelerates to a stop after the freeze (power cut), the frozen pose is
                        //stale mid-spin while the bearing disc recaptures the stopped angle. Track the
                        //live pose while the per-tick change is small (settling), hold while large.
                        SCRATCH_POSE.pushPose();
                        try {
                            ce.applyLocalTransforms(SCRATCH_POSE, 1.0f);
                            var live = SCRATCH_POSE.last().pose();
                            float ax = snap.local.m00(), ay = snap.local.m01(), az = snap.local.m02();
                            float bx = live.m00(), by = live.m01(), bz = live.m02();
                            double dot = (ax * bx + ay * by + az * bz)
                                    / (Math.sqrt(ax * ax + ay * ay + az * az) * Math.sqrt(bx * bx + by * by + bz * bz) + 1.0e-9);
                            if (dot > 0.9986) { //under ~3 deg since the freeze pose: settling, follow it
                                snap.local.set(live);
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            SCRATCH_POSE.popPose();
                        }
                        snap.lastSeenMs = now;
                        continue;
                    }
                } else {
                    snap.frozenControlled = false;
                }
            }
            //Live pose while in range - frozen the moment the player leaves (also the shared refresh
            //path for the freeze tick and the anchor-recapture re-freeze above)
            SCRATCH_POSE.pushPose();
            try {
                ce.applyLocalTransforms(SCRATCH_POSE, 1.0f);
                snap.local.set(SCRATCH_POSE.last().pose());
            } catch (Throwable ignored) {
            } finally {
                SCRATCH_POSE.popPose();
            }
            snap.x = ce.getX();
            snap.y = ce.getY();
            snap.z = ce.getZ();
            snap.dim = dimId;
            snap.lightPacked = DistantLightSampler.sample(level,
                    (int) Math.floor(ce.getX()), (int) Math.floor(ce.getY()), (int) Math.floor(ce.getZ()));
            snap.lastSeenMs = now;
        }

        for (var entry : SNAPSHOTS.entrySet()) {
            if (!seenThisTick.contains(entry.getKey())) {
                entry.getValue().live = false;
            }
        }

        //Leave-behinds are permanent while far away: the entity drops off the client at the server's
        //entity tracking range (a few dozen blocks), far inside the LOD radius, so any time-based
        //expiry deletes the snapshot long before the player is far enough to look back at it. Cleanup
        //is presence-based instead: within a radius where the entity would certainly be tracked, a
        //snapshot whose entity did not appear this tick no longer exists (disassembled/removed).
        //Presence radius clamped inside the render distance: with a tiny view distance 48 blocks can
        //reach past where entities are even guaranteed to be tracked, deleting legitimate snapshots
        double reach = net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        double presenceRadius = Math.min(48.0, Math.max(16.0, reach - 8.0));
        double presenceRadiusSq = presenceRadius * presenceRadius;
        SNAPSHOTS.entrySet().removeIf(entry -> {
            var s = entry.getValue();
            double sx = s.x - camX, sy = s.y - camY, sz = s.z - camZ;
            if (sx * sx + sy * sy + sz * sz > presenceRadiusSq || seenThisTick.contains(entry.getKey())) {
                return false;
            }
            //Grace only for entity-sync lag: any longer and a player who disassembles a structure and
            //walks off crosses the 48-block line before the check fires, leaving a permanent ghost.
            if (now - s.lastSeenMs < 2000) {
                return false;
            }
            if (s.mesh != null) {
                s.mesh.close();
            }
            return true;
        });
        snapshotCount = SNAPSHOTS.size();
    }

    private static CarriageMeshBaker.BakedCarriage bakeContraption(Contraption contraption) {
        List<ShapeBlock> blocks = new ArrayList<>();
        Map<BlockPos, net.neoforged.neoforge.client.model.data.ModelData> blockEntityData = null;
        for (var entry : contraption.getBlocks().entrySet()) {
            BlockPos pos = entry.getKey();
            var state = entry.getValue().state();
            if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                continue;
            }
            if (Math.abs(pos.getX()) > MAX_LOCAL || Math.abs(pos.getY()) > MAX_LOCAL || Math.abs(pos.getZ()) > MAX_LOCAL) {
                continue;
            }
            blocks.add(new ShapeBlock((byte) pos.getX(), (byte) pos.getY(), (byte) pos.getZ(), state));
            //Copycat looks live in the captured block entity nbt, not the state
            var copycatData = me.cortex.voxy.commonImpl.compat.CreateCopycatCompat
                    .materialFromContraptionNbt(state, entry.getValue().nbt());
            if (copycatData != null) {
                if (blockEntityData == null) {
                    blockEntityData = new HashMap<>();
                }
                blockEntityData.put(pos, copycatData);
            }
        }
        return CarriageMeshBaker.bake(blocks, blockEntityData);
    }

    public static Map<UUID, Snapshot> snapshots() {
        return SNAPSHOTS;
    }

    //A contraption that died (disassembled back into blocks, broken, killed) no longer exists - its
    //snapshot must go immediately. Only unloading (the player walking away) freezes a leave-behind.
    public static void removeDead(UUID id) {
        Snapshot snap = SNAPSHOTS.remove(id);
        if (snap != null && snap.mesh != null) {
            snap.mesh.close();
        }
        snapshotCount = SNAPSHOTS.size();
    }

    public static void clearAll() {
        for (var snap : SNAPSHOTS.values()) {
            if (snap.mesh != null) {
                snap.mesh.close();
            }
        }
        SNAPSHOTS.clear();
        snapshotCount = 0;
    }
}
