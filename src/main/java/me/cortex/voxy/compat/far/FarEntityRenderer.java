package me.cortex.voxy.compat.far;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.compat.far.FarEntityProtocol.ItemSnapshot;
import me.cortex.voxy.compat.far.FarPlayerTracker.TrackedPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

final class FarEntityRenderer {
    private static final float WALK_ANIMATION_SCALE = 0.4F;
    private static final AtomicInteger NEXT_PROXY_ID = new AtomicInteger(1_000_000_000);
    private final FarPlayerTracker tracker;
    private final Map<UUID, PlayerProxy> playerProxies = new HashMap<>();
    private final Map<UUID, Entity> vehicleProxies = new HashMap<>();

    FarEntityRenderer(FarPlayerTracker tracker) {
        this.tracker = tracker;
    }

    void clear() {
        for (PlayerProxy proxy : this.playerProxies.values()) {
            proxy.stopRiding();
        }
        for (Entity vehicle : this.vehicleProxies.values()) {
            vehicle.ejectPassengers();
        }
        this.playerProxies.clear();
        this.vehicleProxies.clear();
    }

    void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer localPlayer = minecraft.player;
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        if (level == null || localPlayer == null || poseStack == null || buffers == null) {
            this.clear();
            return;
        }
        if (!FarEntityClient.isEnabled()
                || !level.dimension().location().toString().equals(this.tracker.dimensionKey())) {
            this.clear();
            return;
        }

        for (PlayerProxy proxy : this.playerProxies.values()) {
            proxy.stopRiding();
        }
        Vec3 cameraPosition = event.getCamera().getPosition();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        int maximumDistance = VoxyConfig.CONFIG.getFarEntityRenderDistanceBlocks();
        int vanillaDistance = minecraft.options.getEffectiveRenderDistance() * 16;
        int animationTick = localPlayer.tickCount;
        long now = System.nanoTime();
        Set<UUID> activePlayers = new HashSet<>();
        Set<UUID> activeProxyVehicles = new HashSet<>();
        Set<UUID> renderedProxyVehicles = new HashSet<>();

        for (TrackedPlayer tracked : this.tracker.players()) {
            Vec3 position = tracked.renderPosition(now);
            double distance = position.distanceTo(localPlayer.position());
            if (distance > maximumDistance) {
                continue;
            }
            int chunkX = Mth.floor(position.x) >> 4;
            int chunkZ = Mth.floor(position.z) >> 4;
            boolean realPlayerPresent = level.getPlayerByUUID(tracked.uuid()) != null;
            if (realPlayerPresent && level.hasChunk(chunkX, chunkZ) && distance <= vanillaDistance + 16.0D) {
                continue;
            }

            PlayerProxy player = this.playerProxies.compute(tracked.uuid(), (uuid, current) ->
                    current == null || current.level() != level
                            ? new PlayerProxy(level, tracked.uuid(), tracked.name())
                            : current
            );
            player.apply(tracked, position, maximumDistance,
                    VoxyConfig.CONFIG.farPlayerAnimationDistance > 0
                            && distance <= VoxyConfig.CONFIG.farPlayerAnimationDistance,
                    animationTick, now);
            activePlayers.add(tracked.uuid());

            if (tracked.hasVehicle()) {
                Entity liveVehicle = level.getEntity(tracked.vehicleEntityId());
                boolean useLiveVehicle = liveVehicle != null
                        && tracked.vehicleUuid().equals(liveVehicle.getUUID())
                        && tracked.vehicleTypeId().equals(typeId(liveVehicle));
                Entity vehicle = useLiveVehicle ? liveVehicle : this.getVehicleProxy(level, tracked);
                if (vehicle != null) {
                    if (!useLiveVehicle) {
                        this.applyVehicleState(vehicle, tracked, now);
                        activeProxyVehicles.add(tracked.vehicleUuid());
                    }
                    player.startRiding(vehicle);
                    if (!useLiveVehicle && renderedProxyVehicles.add(tracked.vehicleUuid())) {
                        Vec3 vehiclePosition = tracked.renderVehiclePosition(now);
                        poseStack.pushPose();
                        dispatcher.render(vehicle,
                                vehiclePosition.x - cameraPosition.x,
                                vehiclePosition.y - cameraPosition.y,
                                vehiclePosition.z - cameraPosition.z,
                                tracked.renderVehicleYaw(now), 0.0F,
                                poseStack, buffers, LightTexture.FULL_BRIGHT);
                        poseStack.popPose();
                    }
                }
            }

            poseStack.pushPose();
            dispatcher.render(player,
                    position.x - cameraPosition.x,
                    position.y - cameraPosition.y,
                    position.z - cameraPosition.z,
                    tracked.renderBodyYaw(now), 0.0F,
                    poseStack, buffers, LightTexture.FULL_BRIGHT);
            poseStack.popPose();
        }

        buffers.endBatch();
        this.playerProxies.entrySet().removeIf(entry -> {
            if (!activePlayers.contains(entry.getKey())) {
                entry.getValue().stopRiding();
                return true;
            }
            return false;
        });
        this.vehicleProxies.entrySet().removeIf(entry -> {
            if (!activeProxyVehicles.contains(entry.getKey())) {
                entry.getValue().ejectPassengers();
                return true;
            }
            return false;
        });
    }

    private Entity getVehicleProxy(ClientLevel level, TrackedPlayer tracked) {
        return this.vehicleProxies.compute(tracked.vehicleUuid(), (uuid, current) -> {
            if (current != null && current.level() == level && tracked.vehicleTypeId().equals(typeId(current))) {
                return current;
            }
            return createVehicleProxy(level, tracked.vehicleTypeId());
        });
    }

    private static Entity createVehicleProxy(ClientLevel level, String entityTypeId) {
        try {
            EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(entityTypeId));
            if (entityType == null) {
                return null;
            }
            Entity entity = entityType.create(level);
            if (entity == null) {
                return null;
            }
            entity.setId(nextProxyId());
            entity.noPhysics = true;
            entity.setNoGravity(true);
            entity.setInvisible(false);
            return entity;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void applyVehicleState(Entity vehicle, TrackedPlayer tracked, long now) {
        Vec3 position = tracked.renderVehiclePosition(now);
        float yaw = tracked.renderVehicleYaw(now);
        float pitch = tracked.renderVehiclePitch(now);
        vehicle.setOldPosAndRot();
        vehicle.xo = vehicle.xOld = position.x;
        vehicle.yo = vehicle.yOld = position.y;
        vehicle.zo = vehicle.zOld = position.z;
        vehicle.moveTo(position.x, position.y, position.z, yaw, pitch);
        vehicle.setYRot(yaw);
        vehicle.yRotO = yaw;
        vehicle.setXRot(pitch);
        vehicle.xRotO = pitch;
    }

    private static String typeId(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id == null ? "" : id.toString();
    }

    private static int nextProxyId() {
        return NEXT_PROXY_ID.getAndUpdate(value -> value == Integer.MAX_VALUE ? 1_000_000_000 : value + 1);
    }

    private static Pose pose(TrackedPlayer tracked) {
        if (tracked.gliding()) return Pose.FALL_FLYING;
        if (tracked.swimming()) return Pose.SWIMMING;
        if (tracked.sneaking()) return Pose.CROUCHING;
        return Pose.STANDING;
    }

    private static ItemStack item(ItemSnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            Item resolved = BuiltInRegistries.ITEM.get(ResourceLocation.parse(snapshot.itemId()));
            return resolved == null || resolved == Items.AIR
                    ? ItemStack.EMPTY
                    : new ItemStack(resolved, Math.max(1, snapshot.count()));
        } catch (RuntimeException ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static final class PlayerProxy extends RemotePlayer {
        private final UUID trackedUuid;
        private int maximumDistance;
        private Vec3 lastWalkPosition;
        private int lastWalkTick = Integer.MIN_VALUE;

        PlayerProxy(ClientLevel level, UUID uuid, String name) {
            super(level, new GameProfile(uuid, name));
            this.trackedUuid = uuid;
            this.setId(nextProxyId());
            this.noPhysics = true;
            this.setNoGravity(true);
            this.setInvisible(false);
        }

        void apply(TrackedPlayer tracked, Vec3 position, int maximumDistance,
                   boolean animate, int animationTick, long now) {
            float bodyYaw = tracked.renderBodyYaw(now);
            float headYaw = tracked.renderHeadYaw(now);
            float pitch = tracked.renderPitch(now);
            this.maximumDistance = maximumDistance;
            this.tickCount = animationTick;
            this.setOldPosAndRot();
            this.xo = this.xOld = position.x;
            this.yo = this.yOld = position.y;
            this.zo = this.zOld = position.z;
            this.moveTo(position.x, position.y, position.z, bodyYaw, pitch);
            this.setYRot(bodyYaw);
            this.yRotO = bodyYaw;
            this.setXRot(pitch);
            this.xRotO = pitch;
            this.setYBodyRot(bodyYaw);
            this.yBodyRotO = bodyYaw;
            this.setYHeadRot(headYaw);
            this.yHeadRotO = headYaw;
            this.setShiftKeyDown(tracked.sneaking());
            this.setSwimming(tracked.swimming());
            this.setPose(pose(tracked));
            this.setItemSlot(EquipmentSlot.MAINHAND, item(tracked.mainHand()));
            this.setItemSlot(EquipmentSlot.OFFHAND, item(tracked.offHand()));
            this.setItemSlot(EquipmentSlot.FEET, item(tracked.feet()));
            this.setItemSlot(EquipmentSlot.LEGS, item(tracked.legs()));
            this.setItemSlot(EquipmentSlot.CHEST, item(tracked.chest()));
            this.setItemSlot(EquipmentSlot.HEAD, item(tracked.head()));
            this.setCustomName(Component.literal(tracked.name()));
            this.setCustomNameVisible(VoxyConfig.CONFIG.renderFarPlayerNames);
            this.updateWalkAnimation(position, tracked, animate, animationTick);
        }

        private void updateWalkAnimation(Vec3 position, TrackedPlayer tracked, boolean animate, int tick) {
            if (this.lastWalkPosition == null || tick == this.lastWalkTick) {
                this.lastWalkPosition = position;
                this.lastWalkTick = tick;
                return;
            }
            this.lastWalkTick = tick;
            float speed = 0.0F;
            if (animate && !tracked.gliding() && !tracked.swimming() && !tracked.hasVehicle()) {
                speed = Math.min((float) Mth.length(
                        position.x - this.lastWalkPosition.x, 0.0D,
                        position.z - this.lastWalkPosition.z) * 4.0F, 1.0F);
            }
            this.walkAnimation.setSpeed(speed);
            this.walkAnimation.update(speed, WALK_ANIMATION_SCALE);
            this.lastWalkPosition = position;
        }

        @Override
        protected PlayerInfo getPlayerInfo() {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            PlayerInfo info = connection == null ? null : connection.getPlayerInfo(this.trackedUuid);
            return info != null ? info : super.getPlayerInfo();
        }

        @Override
        public boolean shouldRenderAtSqrDistance(double distanceSquared) {
            double maximum = Math.max(64, this.maximumDistance);
            return distanceSquared <= maximum * maximum;
        }
    }
}
