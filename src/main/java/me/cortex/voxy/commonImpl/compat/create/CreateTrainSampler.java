package me.cortex.voxy.commonImpl.compat.create;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.Train;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.BogeyPose;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.CarriagePose;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.CarriageShapePayload;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ShapeBlock;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ShapeBogey;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.TrainPosesPayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

//Server-side distant train sampler. Create keeps every carriage's positionAnchor/rotationAnchors
//updated each tick even while the train is simulated (chunks unloaded, no entities), so this just
//reads Create.RAILWAYS and streams poses to players between the entity tracking range and the
//distant render radius. Carriage block shapes are pulled once from the serialized contraption NBT
//and cached per (train, carriage). This is the only class in the project that touches Create.
//
//createthreadedtrains compat: that mod moves the whole RAILWAYS tick onto its "Train Worker"
//thread, running concurrently with the server tick. When present, sampling is submitted to the
//same worker queue so it runs serially after the train tick instead of racing it. State maps are
//concurrent because logout/shutdown cleanup still arrives on the server thread.
public final class CreateTrainSampler {
    public static final CreateTrainSampler INSTANCE = new CreateTrainSampler();


    //Create's entity pipeline only covers carriages standing in chunks the client actually renders,
    //i.e. within min(server view-distance, the client's requested view distance). Start streaming a
    //couple of chunks inside that edge so the handover overlaps instead of gapping; 208 caps the
    //floor because entity tracking ends at ~15 chunks no matter how far chunks render.
    private static double minSendDistance(MinecraftServer server, ServerPlayer player) {
        int effectiveViewChunks = Math.min(server.getPlayerList().getViewDistance(), player.requestedViewDistance());
        return Math.max(32, Math.min((effectiveViewChunks - 2) * 16, 208));
    }

    //Diagnostics surfaced by /voxy debug trains (integrated server) and worth having on dedicated
    public static volatile String lastError;
    public static volatile long lastSampleAtMs;
    public static volatile int lastTrainsSeen = -1;
    public static volatile int lastPosePacketsSent;

    private int tickCounter;
    private volatile boolean loggedFirstSend;
    //Per player, grouped by train: which shape ids were sent. Removals drop the train's group so a
    //train re-entering the window gets its shapes again (the client keeps meshes, this is a backstop).
    private final Map<UUID, Map<UUID, Set<Long>>> sentShapes = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> visibleTrains = new ConcurrentHashMap<>();
    //Shape payloads are built once per carriage and reused for every player
    private final Map<Long, CarriageShapePayload> shapeCache = new ConcurrentHashMap<>();
    private final Set<Long> failedShapes = ConcurrentHashMap.newKeySet();

    //Key for the per-round pose cache: a carriage pose is identical for every player in a dimension
    private record PoseKey(long shapeId, ResourceKey<Level> dim) {}

    private static Field serialisedEntityField;

    private static final boolean CTT_LOADED = net.neoforged.fml.ModList.get().isLoaded("createthreadedtrains");
    private static Field cttThreadField;
    private static Method cttSubmitMethod;
    private static boolean cttReflectionFailed;

    private CreateTrainSampler() {}

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (++this.tickCounter < DistantTrainConfig.sampleInterval()) {
            return;
        }
        this.tickCounter = 0;
        var server = event.getServer();
        if (CTT_LOADED && this.submitToTrainWorker(server)) {
            return;
        }
        this.sampleSafe(server);
    }

    private void sampleSafe(MinecraftServer server) {
        try {
            this.sample(server);
            lastSampleAtMs = System.currentTimeMillis();
        } catch (Throwable e) {
            var top = e.getStackTrace().length > 0 ? " @ " + e.getStackTrace()[0] : "";
            lastError = e + top;
            Logger.error("Distant train sampling failed", e);
        }
    }

    //Queue the sample onto createthreadedtrains' worker so it never races the off-thread train tick.
    //The worker is recreated per server run, so the field is re-read every time.
    private boolean submitToTrainWorker(MinecraftServer server) {
        if (cttReflectionFailed) {
            return false;
        }
        try {
            if (cttThreadField == null) {
                Class<?> main = Class.forName("de.mrjulsen.ctt.CreateThreadedTrains");
                cttThreadField = main.getDeclaredField("thread");
                cttThreadField.setAccessible(true);
                cttSubmitMethod = Class.forName("de.mrjulsen.ctt.WorkerThread").getMethod("submitTask", Runnable.class);
            }
            Object worker = cttThreadField.get(null);
            if (worker == null) {
                return false;
            }
            cttSubmitMethod.invoke(worker, (Runnable) () -> this.sampleSafe(server));
            return true;
        } catch (Throwable e) {
            cttReflectionFailed = true;
            Logger.warn("createthreadedtrains present but its worker is unreachable; distant train sampling stays on the server thread", e);
            return false;
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        this.sentShapes.remove(event.getEntity().getUUID());
        this.visibleTrains.remove(event.getEntity().getUUID());
        DistantTrainConfig.removePlayerConfig(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        this.sentShapes.clear();
        this.visibleTrains.clear();
        this.shapeCache.clear();
        this.failedShapes.clear();
        DistantTrainConfig.clearPlayerConfigs();
    }

    //Contraption.fromNBT deserialization per carriage is heavy; a long train entering the window
    //would build every carriage's shape in one server tick. Cap builds per round and let the rest
    //arrive over the next few ticks - the client streams poses regardless and renders each carriage
    //the moment its shape lands, so a shape lagging a tick or two is invisible in practice.
    private static final int SHAPE_BUILDS_PER_ROUND = 8;
    private int shapeBuildsThisRound;

    private void sample(MinecraftServer server) {
        this.shapeBuildsThisRound = 0;
        var players = server.getPlayerList().getPlayers();
        //Every client supplies its own preference. A dedicated server may additionally impose the
        //uniform voxy-server.toml ceiling; an integrated server follows its host's client setting.
        boolean applyServerCeiling = server.isDedicatedServer();
        if (players.isEmpty() || (applyServerCeiling && !DistantTrainConfig.serverEnabled)
                || Create.RAILWAYS.trains.isEmpty()) {
            if (!this.visibleTrains.isEmpty()) {
                //Flush removals for anyone who still has meshes
                for (var player : players) {
                    this.sendRemovals(player, this.visibleTrains.remove(player.getUUID()), Set.of());
                }
                this.visibleTrains.clear();
            }
            return;
        }

        //Snapshot: on the fallback path under createthreadedtrains the map may still be mutated
        //concurrently; a torn copy at worst drops one sampling round (caller catches).
        var trains = new ArrayList<>(Create.RAILWAYS.trains.values());
        lastTrainsSeen = trains.size();
        int posePacketsSent = 0;
        //Each player's stream window is computed below as their requested render distance, optionally
        //clamped by the dedicated-server ceiling. Streaming a band the client never draws is pure waste.
        //A carriage's world pose (anchor + yaw/pitch + bogey poses) depends only on (train, carriage,
        //dimension), not on the observing player - but the anchors and buildBogeyPoses were recomputed
        //once per PLAYER. Memoize the CarriagePose per (shapeId, dimension) for this sample round so N
        //players watching the same train share one computation. CarriagePose is an immutable record, so
        //handing the same instance to every player's packet is safe. Cleared implicitly each round.
        Map<PoseKey, CarriagePose> poseRoundCache = new java.util.HashMap<>();
        for (ServerPlayer player : players) {
            UUID playerId = player.getUUID();
            if (!DistantTrainConfig.enabled(playerId, applyServerCeiling)) {
                Set<UUID> previouslyVisible = this.visibleTrains.remove(playerId);
                this.sendRemovals(player, previouslyVisible, Set.of());
                continue;
            }
            double streamMax = DistantTrainConfig.maxDistance(playerId, applyServerCeiling);
            double streamMaxSq = streamMax * streamMax;
            var playerDim = player.level().dimension();
            var playerPos = player.position();
            var nowVisible = new HashSet<UUID>();
            var shapesByTrain = this.sentShapes.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
            double minDist = minSendDistance(server, player);

            for (Train train : trains) {
                List<CarriagePose> poses = null;
                for (int i = 0; i < train.carriages.size(); i++) {
                    Carriage carriage = train.carriages.get(i);
                    var dce = carriage.getDimensionalIfPresent(playerDim);
                    if (dce == null || dce.positionAnchor == null) {
                        continue;
                    }
                    double distSq = dce.positionAnchor.distanceToSqr(playerPos);
                    if (distSq < minDist * minDist || distSq > streamMaxSq) {
                        continue;
                    }

                    long shapeId = shapeId(train.id, i);
                    //Shape may lag behind (e.g. the carriage has not serialized yet) - poses stream
                    //regardless so the client tracks the train and renders the moment a shape lands
                    var shape = this.getOrBuildShape(player.serverLevel(), train, carriage, i, shapeId);
                    if (shape != null && shapesByTrain.computeIfAbsent(train.id, k -> ConcurrentHashMap.newKeySet()).add(shapeId)) {
                        PacketDistributor.sendToPlayer(player, shape);
                        if (!this.loggedFirstSend) {
                            this.loggedFirstSend = true;
                            Logger.info("Distant train sampling active: first carriage shape sent to " + player.getGameProfile().getName());
                        }
                    }

                    //Pose is player-independent - compute once per (shapeId, dimension) per round
                    CarriagePose pose = poseRoundCache.get(new PoseKey(shapeId, playerDim));
                    if (pose != null) {
                        me.cortex.voxy.commonImpl.PerfStats.trainPoseCacheHit.increment();
                    }
                    if (pose == null) {
                        me.cortex.voxy.commonImpl.PerfStats.trainPoseCacheMiss.increment();
                        Vec3 leading = dce.rotationAnchors.getFirst();
                        Vec3 trailing = dce.rotationAnchors.getSecond();
                        float yaw = 0, pitch = 0;
                        if (leading != null && trailing != null) {
                            //Same convention as Carriage.DimensionalCarriageEntity.alignEntity
                            Vec3 diff = leading.subtract(trailing);
                            yaw = (float) (Math.atan2(diff.z, diff.x) * (180.0 / Math.PI)) + 180.0f;
                            pitch = (float) (-Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z)) * (180.0 / Math.PI));
                        }
                        pose = new CarriagePose(i, shapeId,
                                dce.positionAnchor.x, dce.positionAnchor.y, dce.positionAnchor.z, yaw, pitch,
                                buildBogeyPoses(train, carriage, playerDim));
                        poseRoundCache.put(new PoseKey(shapeId, playerDim), pose);
                    }

                    if (poses == null) {
                        poses = new ArrayList<>(train.carriages.size());
                    }
                    poses.add(pose);
                }

                if (poses != null) {
                    nowVisible.add(train.id);
                    PacketDistributor.sendToPlayer(player,
                            new TrainPosesPayload(train.id, playerDim.location(), poses));
                    posePacketsSent++;
                }
            }

            var previouslyVisible = this.visibleTrains.put(playerId, nowVisible);
            this.sendRemovals(player, previouslyVisible, nowVisible);
        }
        lastPosePacketsSent = posePacketsSent;
    }

    private void sendRemovals(ServerPlayer player, Set<UUID> previous, Set<UUID> current) {
        if (previous == null) {
            return;
        }
        var shapesByTrain = this.sentShapes.get(player.getUUID());
        for (UUID trainId : previous) {
            if (!current.contains(trainId)) {
                PacketDistributor.sendToPlayer(player,
                        new TrainPosesPayload(trainId, ResourceLocation.fromNamespaceAndPath("voxy", "removed"), List.of()));
                if (shapesByTrain != null) {
                    shapesByTrain.remove(trainId);
                }
            }
        }
    }

    private CarriageShapePayload getOrBuildShape(ServerLevel level, Train train, Carriage carriage, int carriageIndex, long shapeId) {
        var cached = this.shapeCache.get(shapeId);
        if (cached != null) {
            return cached;
        }
        if (this.failedShapes.contains(shapeId)) {
            return null;
        }
        //Budget the heavy deserialize+build; over budget this round, retry next tick (not a failure)
        if (this.shapeBuildsThisRound >= SHAPE_BUILDS_PER_ROUND) {
            me.cortex.voxy.commonImpl.PerfStats.trainShapeBuildDeferred.increment();
            return null;
        }
        try {
            Contraption contraption = resolveContraption(level, carriage);
            if (contraption == null) {
                //Not failed - the carriage may simply not have serialized yet; retry next round
                //(no build happened, so don't spend a budget slot on it)
                return null;
            }
            //A real shape build is proceeding (block iteration, and possibly an fromNBT above) - count it
            this.shapeBuildsThisRound++;
            List<ShapeBlock> blocks = new ArrayList<>();
            for (var entry : contraption.getBlocks().entrySet()) {
                var pos = entry.getKey();
                var state = entry.getValue().state();
                if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                    continue;
                }
                if (Math.abs(pos.getX()) > 127 || Math.abs(pos.getY()) > 127 || Math.abs(pos.getZ()) > 127) {
                    continue;
                }
                //Copycat looks live in block entity data, not the state - carry the material slice
                CompoundTag renderNbt = me.cortex.voxy.commonImpl.compat.CopycatCommon
                        .renderNbt(state, entry.getValue().nbt());
                blocks.add(new ShapeBlock((byte) pos.getX(), (byte) pos.getY(), (byte) pos.getZ(), state,
                        java.util.Optional.ofNullable(renderNbt)));
            }
            if (blocks.isEmpty()) {
                this.failedShapes.add(shapeId);
                return null;
            }
            List<ShapeBogey> bogeys = new ArrayList<>(2);
            collectBogeyInfo(carriage.bogeys.getFirst(), bogeys);
            if (carriage.isOnTwoBogeys()) {
                collectBogeyInfo(carriage.bogeys.getSecond(), bogeys);
            }
            var payload = new CarriageShapePayload(train.id, carriageIndex, shapeId, resolveInitialYaw(carriage), blocks, bogeys);
            this.shapeCache.put(shapeId, payload);
            return payload;
        } catch (Throwable e) {
            this.failedShapes.add(shapeId);
            Logger.error("Failed to build distant shape for train " + train.id + " carriage " + carriageIndex, e);
            return null;
        }
    }

    private static void collectBogeyInfo(CarriageBogey bogey, List<ShapeBogey> out) {
        var style = bogey.getStyle();
        var size = bogey.getSize();
        var data = bogey.bogeyData != null ? bogey.bogeyData.copy() : new CompoundTag();
        out.add(new ShapeBogey(style.id, size.id(), size.wheelRadius(), data));
    }

    //World poses for the carriage's bogeys, computed the same way CarriageBogey.updateAngles does
    //(that method only runs client side on real entities, so simulated trains need it re-derived
    //from the travelling points). Returns an empty list unless every bogey is cleanly on the graph
    //in the player's dimension - the client skips bogey rendering rather than guessing.
    private static List<BogeyPose> buildBogeyPoses(Train train, Carriage carriage, ResourceKey<Level> dim) {
        if (train.derailed || train.graph == null) {
            return List.of();
        }
        List<BogeyPose> out = new ArrayList<>(2);
        if (!appendBogeyPose(train, carriage.bogeys.getFirst(), dim, out)) {
            return List.of();
        }
        if (carriage.isOnTwoBogeys() && !appendBogeyPose(train, carriage.bogeys.getSecond(), dim, out)) {
            return List.of();
        }
        return out;
    }

    private static boolean appendBogeyPose(Train train, CarriageBogey bogey, ResourceKey<Level> dim, List<BogeyPose> out) {
        var lead = bogey.leading();
        var trail = bogey.trailing();
        if (lead.edge == null || trail.edge == null || !dim.equals(bogey.getDimension())) {
            return false;
        }
        Vec3 p1 = lead.getPosition(train.graph);
        Vec3 p2 = trail.getPosition(train.graph);
        Vec3 anchor = p1.add(p2).scale(0.5);
        double dx = p1.x - p2.x, dy = p1.y - p2.y, dz = p1.z - p2.z;
        //CarriageBogey.updateAngles stores yaw = -yRot, pitch = xRot
        float yRot = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) + 90.0f;
        float xRot = (float) (Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI));
        out.add(new BogeyPose(anchor.x, anchor.y, anchor.z, -yRot, xRot, bogey.isUpsideDown()));
        return true;
    }

    //Assembly orientation, matching what applyLocalTransforms rotates by on a real entity. Read
    //from the live entity when present, else from the serialized entity NBT.
    private static float resolveInitialYaw(Carriage carriage) {
        try {
            var entity = carriage.anyAvailableEntity();
            if (entity != null) {
                return entity.getInitialYaw();
            }
            if (serialisedEntityField == null) {
                serialisedEntityField = Carriage.class.getDeclaredField("serialisedEntity");
                serialisedEntityField.setAccessible(true);
            }
            CompoundTag tag = (CompoundTag) serialisedEntityField.get(carriage);
            if (tag != null && tag.contains("InitialOrientation")) {
                return net.minecraft.core.Direction.valueOf(tag.getString("InitialOrientation").toUpperCase(java.util.Locale.ROOT)).toYRot();
            }
        } catch (Throwable e) {
            Logger.warn("Could not resolve initial yaw for a distant carriage, defaulting to 0", e);
        }
        return 0;
    }

    //Prefer a live entity's contraption; fall back to deserializing the carriage's saved NBT
    //(the only place the blocks live while the train is simulated).
    private static Contraption resolveContraption(ServerLevel level, Carriage carriage) throws Exception {
        var entity = carriage.anyAvailableEntity();
        if (entity != null && entity.getContraption() != null) {
            return entity.getContraption();
        }
        if (serialisedEntityField == null) {
            serialisedEntityField = Carriage.class.getDeclaredField("serialisedEntity");
            serialisedEntityField.setAccessible(true);
        }
        CompoundTag serialized = (CompoundTag) serialisedEntityField.get(carriage);
        if (serialized == null || !serialized.contains("Contraption")) {
            return null;
        }
        return Contraption.fromNBT(level, serialized.getCompound("Contraption"), false);
    }

    private static long shapeId(UUID trainId, int carriageIndex) {
        return trainId.getMostSignificantBits() ^ trainId.getLeastSignificantBits() ^ (carriageIndex * 0x9E3779B97F4A7C15L);
    }

    //Full server-side pipeline dump for /voxy debug trains on an integrated server: every train,
    //every carriage, with distance, window verdict, shape cache state and shape source health.
    public static String debugDump(ServerPlayer player) {
        var sb = new StringBuilder("sampler: lastRun=");
        sb.append(lastSampleAtMs == 0 ? "never" : (System.currentTimeMillis() - lastSampleAtMs) + "ms ago")
                .append(" trainsSeen=").append(lastTrainsSeen)
                .append(" posePackets=").append(lastPosePacketsSent)
                .append(" shapeCache=").append(INSTANCE.shapeCache.size())
                .append(" failedShapes=").append(INSTANCE.failedShapes.size())
                .append(" ctt=").append(CTT_LOADED);
        if (lastError != null) {
            sb.append("\n lastError=").append(lastError);
        }
        var dim = player.level().dimension();
        var pos = player.position();
        double minDist = minSendDistance(player.serverLevel().getServer(), player);
        boolean applyServerCeiling = player.serverLevel().getServer().isDedicatedServer();
        double streamMax = DistantTrainConfig.maxDistance(player.getUUID(), applyServerCeiling);
        sb.append("\n distantTrains=").append(DistantTrainConfig.enabled(player.getUUID(), applyServerCeiling))
                .append(" interval=").append(DistantTrainConfig.sampleInterval()).append("t")
                .append(" window=[").append((int) minDist).append(", ").append((int) streamMax)
                .append("] (min=view-derived, max=min(client,server))");
        for (Train train : new ArrayList<>(Create.RAILWAYS.trains.values())) {
            sb.append("\n train ").append(train.id.toString(), 0, 8)
                    .append(" carriages=").append(train.carriages.size())
                    .append(" graph=").append(train.graph != null)
                    .append(" derailed=").append(train.derailed);
            for (int i = 0; i < train.carriages.size(); i++) {
                Carriage carriage = train.carriages.get(i);
                sb.append("\n  #").append(i);
                var dce = carriage.getDimensionalIfPresent(dim);
                if (dce == null) {
                    sb.append(" <no presence in ").append(dim.location()).append('>');
                    continue;
                }
                if (dce.positionAnchor == null) {
                    sb.append(" positionAnchor=null");
                    continue;
                }
                int dist = (int) Math.sqrt(dce.positionAnchor.distanceToSqr(pos));
                long sid = shapeId(train.id, i);
                String shapeState = INSTANCE.shapeCache.containsKey(sid) ? "cached"
                        : (INSTANCE.failedShapes.contains(sid) ? "FAILED" : "pending");
                sb.append(" dist=").append(dist)
                        .append(dist < minDist ? " (inside min: Create's own entity renders)"
                                : (dist > streamMax ? " (beyond max)" : " (IN WINDOW)"))
                        .append(" shapeId=").append(Long.toHexString(sid))
                        .append(" shape=").append(shapeState)
                        .append(" src=").append(describeShapeSource(carriage));
            }
        }
        return sb.toString();
    }

    private static String describeShapeSource(Carriage carriage) {
        try {
            var entity = carriage.anyAvailableEntity();
            if (entity != null && entity.getContraption() != null) {
                return "live-entity";
            }
            if (serialisedEntityField == null) {
                serialisedEntityField = Carriage.class.getDeclaredField("serialisedEntity");
                serialisedEntityField.setAccessible(true);
            }
            CompoundTag tag = (CompoundTag) serialisedEntityField.get(carriage);
            if (tag == null || tag.isEmpty()) {
                return "serialised-nbt-empty";
            }
            return tag.contains("Contraption") ? "serialised-nbt-ok" : "serialised-nbt-missing-contraption";
        } catch (Throwable e) {
            return "probe-error:" + e;
        }
    }
}
