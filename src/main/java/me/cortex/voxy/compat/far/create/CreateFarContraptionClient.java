package me.cortex.voxy.compat.far.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.mixin.create.ControlledContraptionEntityAccessor;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.compat.far.FarContraptionClient;
import me.cortex.voxy.compat.far.FarEntityProtocol.ContraptionBatch;
import me.cortex.voxy.compat.far.FarEntityProtocol.ContraptionSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.Direction.Axis;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Client-side detached proxy renderer for Create structures. */
public final class CreateFarContraptionClient implements FarContraptionClient {
    private static final long INTERPOLATION_NANOS = TimeUnit.MILLISECONDS.toNanos(550L);
    private static final AtomicInteger NEXT_PROXY_ID = new AtomicInteger(1_100_000_000);

    private final Map<UUID, TrackedContraption> contraptions = new HashMap<>();
    private String dimensionKey = "";
    private int generation;

    @Override
    public void clear() {
        this.contraptions.clear();
        this.dimensionKey = "";
        this.generation = 0;
    }

    @Override
    public void apply(ContraptionBatch batch) {
        int nextGeneration = ++this.generation;
        this.dimensionKey = batch.dimensionKey();
        for (ContraptionSnapshot snapshot : batch.contraptions()) {
            this.contraptions.compute(snapshot.id(), (id, current) -> {
                if (current == null) return new TrackedContraption(snapshot, nextGeneration);
                current.apply(snapshot, nextGeneration);
                return current;
            });
        }
        this.contraptions.entrySet().removeIf(entry -> entry.getValue().generation != nextGeneration);
    }

    @Override
    public void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        PoseStack poseStack = event.getPoseStack();
        if (level == null || minecraft.player == null || poseStack == null
                || !VoxyConfig.CONFIG.enableFarCreateContraptionRendering
                || !level.dimension().location().toString().equals(this.dimensionKey)) {
            if (level == null) this.clear();
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        Vec3 viewer = minecraft.player.position();
        int maximumDistance = VoxyConfig.CONFIG.getFarCreateContraptionDistanceBlocks();
        double maximumDistanceSquared = (double) maximumDistance * maximumDistance;
        long now = System.nanoTime();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();

        FarContraptionRenderContext.enter();
        try {
            for (TrackedContraption tracked : this.contraptions.values()) {
                Vec3 position = tracked.position(now);
                if (position.distanceToSqr(viewer) > maximumDistanceSquared) continue;

                Entity live = tracked.liveEntityId < 0 ? null : level.getEntity(tracked.liveEntityId);
                if (live instanceof AbstractContraptionEntity) continue;

                AbstractContraptionEntity proxy = tracked.proxy(level);
                if (proxy == null || proxy.getContraption() == null) continue;
                tracked.applyPose(proxy, position, now);
                if (!bindCarriage(proxy, tracked.trainId, tracked.carriageIndex)) continue;

                poseStack.pushPose();
                dispatcher.render(proxy,
                        position.x - camera.x,
                        position.y - camera.y,
                        position.z - camera.z,
                        0.0F, 0.0F, poseStack, buffers, LightTexture.FULL_BRIGHT);
                poseStack.popPose();
            }
            buffers.endBatch();
        } finally {
            FarContraptionRenderContext.exit();
        }
    }

    private static boolean bindCarriage(AbstractContraptionEntity proxy, UUID trainId, int carriageIndex) {
        if (!(proxy instanceof CarriageContraptionEntity carriageEntity)) return true;
        carriageEntity.validForRender = true;
        carriageEntity.firstPositionUpdate = false;
        if (carriageEntity.getCarriage() != null) return true;
        if (trainId == null || carriageIndex < 0) return false;
        Train train = CreateClient.RAILWAYS.trains.get(trainId);
        if (train == null || carriageIndex >= train.carriages.size()) return false;
        carriageEntity.setCarriage(train.carriages.get(carriageIndex));
        return true;
    }

    private static final class TrackedContraption {
        private final UUID id;
        private Vec3 fromPosition;
        private Vec3 toPosition;
        private float fromYaw;
        private float toYaw;
        private float fromPitch;
        private float toPitch;
        private float fromAngle;
        private float toAngle;
        private long snapshotNanos;
        private int rotationAxis;
        private int liveEntityId;
        private UUID trainId;
        private int carriageIndex;
        private int definitionHash;
        private CompoundTag definition;
        private AbstractContraptionEntity proxy;
        private ClientLevel proxyLevel;
        private int generation;

        private TrackedContraption(ContraptionSnapshot snapshot, int generation) {
            this.id = snapshot.id();
            Vec3 position = new Vec3(snapshot.x(), snapshot.y(), snapshot.z());
            this.fromPosition = this.toPosition = position;
            this.fromYaw = this.toYaw = snapshot.yaw();
            this.fromPitch = this.toPitch = snapshot.pitch();
            this.fromAngle = this.toAngle = snapshot.angle();
            this.snapshotNanos = System.nanoTime();
            this.definitionHash = snapshot.definitionHash();
            this.definition = snapshot.definition() == null ? null : snapshot.definition().copy();
            this.applyFields(snapshot, generation);
        }

        private void apply(ContraptionSnapshot snapshot, int generation) {
            long now = System.nanoTime();
            this.fromPosition = this.position(now);
            this.fromYaw = this.yaw(now);
            this.fromPitch = this.pitch(now);
            this.fromAngle = this.angle(now);
            this.toPosition = new Vec3(snapshot.x(), snapshot.y(), snapshot.z());
            this.toYaw = snapshot.yaw();
            this.toPitch = snapshot.pitch();
            this.toAngle = snapshot.angle();
            this.snapshotNanos = now;
            if (snapshot.definition() != null) {
                this.definition = snapshot.definition().copy();
                if (this.definitionHash != snapshot.definitionHash()) {
                    this.proxy = null;
                    this.proxyLevel = null;
                }
            }
            this.definitionHash = snapshot.definitionHash();
            this.applyFields(snapshot, generation);
        }

        private void applyFields(ContraptionSnapshot snapshot, int generation) {
            this.liveEntityId = snapshot.liveEntityId();
            this.trainId = snapshot.trainId();
            this.carriageIndex = snapshot.carriageIndex();
            this.rotationAxis = snapshot.rotationAxis();
            this.generation = generation;
        }

        private AbstractContraptionEntity proxy(ClientLevel level) {
            if (this.proxy != null && this.proxyLevel == level) return this.proxy;
            if (this.definition == null) return null;
            try {
                Entity entity = EntityType.create(this.definition.copy(), level).orElse(null);
                if (!(entity instanceof AbstractContraptionEntity contraption)) return null;
                contraption.setUUID(this.id);
                contraption.setId(NEXT_PROXY_ID.getAndIncrement());
                contraption.noPhysics = true;
                contraption.setNoGravity(true);
                this.proxy = contraption;
                this.proxyLevel = level;
                return contraption;
            } catch (RuntimeException e) {
                Logger.error("Could not create a far Create contraption proxy", e);
                this.definition = null;
                return null;
            }
        }

        private void applyPose(AbstractContraptionEntity proxy, Vec3 position, long now) {
            proxy.setOldPosAndRot();
            proxy.xo = proxy.xOld = position.x;
            proxy.yo = proxy.yOld = position.y;
            proxy.zo = proxy.zOld = position.z;
            proxy.moveTo(position.x, position.y, position.z, 0.0F, 0.0F);
            if (proxy instanceof OrientedContraptionEntity oriented) {
                float yaw = this.yaw(now);
                float pitch = this.pitch(now);
                oriented.prevYaw = oriented.yaw = oriented.targetYaw = yaw;
                oriented.prevPitch = oriented.pitch = pitch;
            } else if (proxy instanceof ControlledContraptionEntity controlled) {
                float angle = this.angle(now);
                if (this.rotationAxis >= 0 && this.rotationAxis < Axis.values().length) {
                    controlled.setRotationAxis(Axis.values()[this.rotationAxis]);
                }
                ((ControlledContraptionEntityAccessor) controlled).voxy$setPreviousAngle(angle);
                controlled.setAngle(angle);
            }
        }

        private float progress(long now) {
            return Mth.clamp((float) (now - this.snapshotNanos) / INTERPOLATION_NANOS, 0.0F, 1.0F);
        }

        private Vec3 position(long now) {
            return this.fromPosition.lerp(this.toPosition, this.progress(now));
        }

        private float yaw(long now) {
            return Mth.rotLerp(this.progress(now), this.fromYaw, this.toYaw);
        }

        private float pitch(long now) {
            return Mth.rotLerp(this.progress(now), this.fromPitch, this.toPitch);
        }

        private float angle(long now) {
            return Mth.rotLerp(this.progress(now), this.fromAngle, this.toAngle);
        }
    }
}
