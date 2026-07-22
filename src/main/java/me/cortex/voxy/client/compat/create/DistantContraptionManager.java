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
        //The blocks the mesh was built from. Kept because nothing else keeps them: the entity is the
        //only other copy and it is gone by the time the snapshot matters, and the mesh itself is an
        //opaque VBO. Without this a snapshot can never be re-baked, only held or lost - which is what
        //makes the resident set a one-way ratchet. About 12 bytes a block plus the state reference.
        Source source;
        //Vertex bytes this snapshot's mesh took when it last had one. Kept across a drop so admission
        //can ask whether rebuilding it would exceed the budget, rather than only whether there is room
        //right now - the two differ by exactly the size of the thing being admitted, which is what made
        //rebuild and evict chase each other every tick.
        long lastMeshBytes;
        //M_local from AbstractContraptionEntity.applyLocalTransforms; the world position is kept
        //separately as doubles so the draw can be camera-relative without float world-coord error.
        final Matrix4f local = new Matrix4f();
        double x, y, z;
        ResourceLocation dim;
        int lightPacked = -1;
        long lastSeenMs;
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
        public Source source() { return this.source; }
    }

    private static final Map<UUID, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    //Read from storage on world entry and baked a few per tick, nearest first, so re-entering a world
    //with a lot of stored structures does not stall on one frame's worth of mesh uploads.
    private static final int BAKES_PER_TICK = 2;
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
        loadStoredOnce(level);
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
                    var collected = collectBlocks(contraption);
                    snap.mesh = bakeBlocks(collected);
                    snap.source = snap.mesh == null ? null : collected;
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

        var storage = storageFor(level);
        for (var entry : SNAPSHOTS.entrySet()) {
            if (!seenThisTick.contains(entry.getKey())) {
                var snap = entry.getValue();
                //The tick it stops being live is the tick its pose stops changing, so that is when the
                //record is worth writing. Writing while live would rewrite the same blocks every tick
                //for a pose that is about to change again.
                if (snap.live && storage != null) {
                    ContraptionStore.save(storage, entry.getKey(), snap);
                }
                snap.live = false;
            }
        }

        bakeDormant(camX, camY, camZ, maxDist);

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

        //An upper bound the presence check cannot provide. Presence only fires within a few dozen blocks,
        //so a snapshot the player leaves behind and never walks back to is kept for the whole session -
        //and one left in another dimension is kept forever, since the renderer skips it on dim and the
        //check above never looks at dim either. Both cost the same VBO as a visible one.
        //
        //This is an addition to the presence check, not a replacement: a time-based expiry would delete
        //legitimate snapshots long before the player is far enough away to look back at them, which is
        //why there is none. Distance is safe because anything past the render radius is not drawn.
        double evictDistSq = (maxDist + 32.0) * (maxDist + 32.0);
        SNAPSHOTS.entrySet().removeIf(entry -> {
            var s = entry.getValue();
            if (seenThisTick.contains(entry.getKey())) {
                return false;
            }
            //Another dimension's snapshot is not coming back into view here, and the renderer filters on
            //dimension anyway, so that one goes entirely - the record is on disk if it is worth keeping.
            if (!dimId.equals(s.dim)) {
                dropMesh(s);
                return true;
            }
            double sx = s.x - camX, sy = s.y - camY, sz = s.z - camZ;
            if ((sx * sx + sy * sy + sz * sz) > evictDistSq) {
                //Only the mesh. The block list it was built from is a few kilobytes against a few
                //hundred for the mesh, and keeping it is what lets the structure come back on approach
                //rather than waiting for the next world load to read it off disk again.
                dropMesh(s);
            }
            return false;
        });

        enforceGpuBudget(camX, camY, camZ);
        snapshotCount = SNAPSHOTS.size();
    }

    //Everything a bake consumes, kept together because both halves come out of the same walk over the
    //contraption and both are needed to reproduce it - the copycat model data is read from block entity
    //nbt that goes away with the entity.
    public record Source(List<ShapeBlock> blocks,
                         Map<BlockPos, net.neoforged.neoforge.client.model.data.ModelData> modelData) {
        public int blockCount() {
            return this.blocks.size();
        }
    }

    private static Source collectBlocks(Contraption contraption) {
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
        return new Source(blocks, blockEntityData);
    }

    private static CarriageMeshBaker.BakedCarriage bakeBlocks(Source source) {
        return CarriageMeshBaker.bake(source.blocks(), source.modelData());
    }


    private static me.cortex.voxy.common.config.section.SectionStorage storageFor(ClientLevel level) {
        var engine = me.cortex.voxy.commonImpl.WorldIdentifier.ofEngineNullable(level);
        return engine == null ? null : engine.storage;
    }

    //Which dimension's records have been read. Reading is driven from the tick rather than a level
    //event because it needs voxy's world engine for the dimension to exist, and nothing guarantees that
    //has happened by the time a level load fires - a miss there would leave the feature silently doing
    //nothing, which is indistinguishable from having stored nothing.
    private static ResourceLocation loadedFor;

    private static void loadStoredOnce(ClientLevel level) {
        var here = level.dimension().location();
        if (here.equals(loadedFor)) {
            return;
        }
        var storage = storageFor(level);
        if (storage == null) {
            //Engine not up yet - try again next tick
            return;
        }
        loadedFor = here;
        loadStored(level);
    }

    public static void loadStored(ClientLevel level) {
        var storage = storageFor(level);
        if (storage == null) {
            return;
        }
        var here = level.dimension().location();
        int restored = 0;
        for (var entry : ContraptionStore.loadAll(storage)) {
            //Another dimension's records stay on disk; they are read again when the player goes there
            if (!here.equals(entry.dim()) || SNAPSHOTS.containsKey(entry.id())) {
                continue;
            }
            //Dormant: it knows what it is made of and where it stood, and nothing has been uploaded for
            //it yet. The same pass that rebuilds an evicted snapshot picks these up, nearest first, so
            //there is one path for "came back into range" and "was just read off disk".
            var snap = new Snapshot();
            snap.source = entry.source();
            snap.local.set(entry.pose());
            snap.x = entry.x();
            snap.y = entry.y();
            snap.z = entry.z();
            snap.dim = entry.dim();
            snap.lastSeenMs = System.currentTimeMillis();
            snap.live = false;
            SNAPSHOTS.put(entry.id(), snap);
            restored++;
        }
        snapshotCount = SNAPSHOTS.size();
        if (restored != 0) {
            me.cortex.voxy.common.Logger.info("Restored " + restored + " distant contraption(s) for " + here);
        }
    }



    private static void dropMesh(Snapshot snap) {
        if (snap.mesh != null) {
            snap.lastMeshBytes = snap.mesh.mesh.gpuByteSize();
            snap.mesh.close();
            snap.mesh = null;
            me.cortex.voxy.commonImpl.PerfStats.contraptionSnapshotEvicted.increment();
        }
    }

    //A snapshot that still knows what it is made of but has no mesh right now. It draws nothing and
    //costs no vertex memory until something brings it back.
    private static boolean isDormant(Snapshot snap) {
        return snap.mesh == null && snap.source != null && !snap.bakeGaveNothing;
    }

    //Vertex memory is the bound that matters - one dense structure can hold as much as a hundred small
    //ones at the same distance, so a distance cap alone says nothing about what is actually held.
    //Furthest first, because that is the one whose absence is least likely to be noticed and the one
    //least likely to be wanted back soon.
    private static void enforceGpuBudget(double camX, double camY, double camZ) {
        long budget = (long) VoxyConfig.CONFIG.distantContraptionGpuBudgetMiB * 1024L * 1024L;
        if (budget <= 0) {
            return;
        }
        long resident = 0;
        for (var snap : SNAPSHOTS.values()) {
            if (snap.mesh != null) {
                resident += snap.mesh.mesh.gpuByteSize();
            }
        }
        //Down to a fraction of the budget rather than exactly to it, or the next structure to come into
        //range evicts one and the one after that evicts it back
        long target = (budget * 9L) / 10L;
        while (resident > budget) {
            Snapshot furthest = null;
            double furthestDistSq = -1;
            for (var snap : SNAPSHOTS.values()) {
                if (snap.mesh == null || snap.live) {
                    continue;
                }
                double dx = snap.x - camX, dy = snap.y - camY, dz = snap.z - camZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > furthestDistSq) {
                    furthestDistSq = distSq;
                    furthest = snap;
                }
            }
            if (furthest == null) {
                //Everything left is live, i.e. Create is drawing it and we are not holding it for long
                break;
            }
            resident -= furthest.mesh.mesh.gpuByteSize();
            dropMesh(furthest);
            if (resident <= target) {
                break;
            }
        }
        residentGpuBytes = resident;
    }

    //Rebuilds a few dormant snapshots per tick, nearest first, while there is budget for them. Same
    //pacing as the restore path for the same reason: baking uploads a buffer.
    private static void bakeDormant(double camX, double camY, double camZ, double maxDist) {
        long budget = (long) VoxyConfig.CONFIG.distantContraptionGpuBudgetMiB * 1024L * 1024L;
        double maxDistSq = maxDist * maxDist;
        //Candidates rejected for size this tick. Without this the loop keeps picking the same nearest
        //one, finds it does not fit, and burns its whole allowance doing nothing.
        var tooBig = new java.util.HashSet<Snapshot>();
        for (int done = 0; done < BAKES_PER_TICK; done++) {
            Snapshot nearest = null;
            double nearestDistSq = Double.MAX_VALUE;
            for (var snap : SNAPSHOTS.values()) {
                if (!isDormant(snap) || tooBig.contains(snap)) {
                    continue;
                }
                double dx = snap.x - camX, dy = snap.y - camY, dz = snap.z - camZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > maxDistSq || distSq >= nearestDistSq) {
                    continue;
                }
                nearestDistSq = distSq;
                nearest = snap;
            }
            if (nearest == null) {
                return;
            }
            //Would rebuilding it overflow the budget? Asking whether there is room now instead admits a
            //mesh that immediately puts the total over, the eviction pass takes it straight back out,
            //and the two repeat every tick - a full bake and buffer upload, twenty times a second.
            //A snapshot never yet baked has no size to check, so it is admitted and measured.
            if (budget > 0 && nearest.lastMeshBytes > 0
                    && residentGpuBytes + nearest.lastMeshBytes > budget) {
                tooBig.add(nearest);
                continue;
            }
            var mesh = bakeBlocks(nearest.source);
            if (mesh == null) {
                nearest.bakeGaveNothing = true;
                continue;
            }
            nearest.mesh = mesh;
            nearest.lastMeshBytes = mesh.mesh.gpuByteSize();
            if (nearest.lightPacked < 0) {
                //Sampled rather than stored: the sampler reads voxy's own voxel store, so it answers for
                //an unloaded chunk, and a value taken now matches the terrain it will be drawn against.
                //-1 is also the "never refreshed" sentinel the freeze logic reads, so it has to go.
                var mc = Minecraft.getInstance();
                if (mc.level != null) {
                    nearest.lightPacked = DistantLightSampler.sample(mc.level,
                            (int) Math.floor(nearest.x), (int) Math.floor(nearest.y), (int) Math.floor(nearest.z));
                }
            }
            residentGpuBytes += mesh.mesh.gpuByteSize();
        }
    }

    public static long residentGpuBytes() {
        return residentGpuBytes;
    }

    private static volatile long residentGpuBytes;

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
        //And out of storage, or the next world entry restores a structure that was taken apart. This is
        //the one removal that means "gone", as opposed to the distance and presence checks which only
        //mean "not here right now".
        var level = Minecraft.getInstance().level;
        if (level != null) {
            var storage = storageFor(level);
            if (storage != null) {
                ContraptionStore.remove(storage, id);
            }
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
        loadedFor = null;
        snapshotCount = 0;
    }
}
