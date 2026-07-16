package me.cortex.voxy.compat.far;

import me.cortex.voxy.compat.far.FarEntityProtocol.ItemSnapshot;
import me.cortex.voxy.compat.far.FarEntityProtocol.PlayerBatch;
import me.cortex.voxy.compat.far.FarEntityProtocol.PlayerSnapshot;
import me.cortex.voxy.compat.far.FarEntityProtocol.VehicleSnapshot;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class FarPlayerTracker {
    private final Map<UUID, TrackedPlayer> players = new HashMap<>();
    private String dimensionKey = "";
    private int generation;

    void clear() {
        this.players.clear();
        this.dimensionKey = "";
        this.generation = 0;
    }

    boolean isEmpty() {
        return this.players.isEmpty();
    }

    void apply(PlayerBatch batch) {
        int nextGeneration = this.generation + 1;
        this.generation = nextGeneration;
        this.dimensionKey = batch.dimensionKey();
        for (PlayerSnapshot snapshot : batch.players()) {
            TrackedPlayer current = this.players.get(snapshot.uuid());
            if (current == null) {
                this.players.put(snapshot.uuid(), new TrackedPlayer(snapshot, nextGeneration));
            } else {
                current.apply(snapshot, nextGeneration);
            }
        }
        Iterator<TrackedPlayer> iterator = this.players.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().generation() != nextGeneration) iterator.remove();
        }
    }

    String dimensionKey() {
        return this.dimensionKey;
    }

    Collection<TrackedPlayer> players() {
        return this.players.values();
    }

    static final class TrackedPlayer {
        private static final long INTERPOLATION_NANOS = TimeUnit.MILLISECONDS.toNanos(550L);
        private final UUID uuid;
        private String name;
        private Vec3 fromPosition;
        private Vec3 toPosition;
        private long snapshotNanos;
        private float fromBodyYaw;
        private float toBodyYaw;
        private float fromHeadYaw;
        private float toHeadYaw;
        private float fromPitch;
        private float toPitch;
        private boolean sneaking;
        private boolean gliding;
        private boolean swimming;
        private ItemSnapshot mainHand;
        private ItemSnapshot offHand;
        private ItemSnapshot feet;
        private ItemSnapshot legs;
        private ItemSnapshot chest;
        private ItemSnapshot head;
        private UUID vehicleUuid;
        private int vehicleEntityId;
        private String vehicleTypeId;
        private Vec3 fromVehiclePosition;
        private Vec3 toVehiclePosition;
        private float fromVehicleYaw;
        private float toVehicleYaw;
        private float fromVehiclePitch;
        private float toVehiclePitch;
        private int generation;

        TrackedPlayer(PlayerSnapshot snapshot, int generation) {
            this.uuid = snapshot.uuid();
            Vec3 position = new Vec3(snapshot.x(), snapshot.y(), snapshot.z());
            this.fromPosition = position;
            this.toPosition = position;
            this.fromBodyYaw = this.toBodyYaw = snapshot.bodyYaw();
            this.fromHeadYaw = this.toHeadYaw = snapshot.headYaw();
            this.fromPitch = this.toPitch = snapshot.pitch();
            long now = System.nanoTime();
            this.snapshotNanos = now;
            this.applyFields(snapshot);
            this.applyVehicle(snapshot.vehicle(), false, now);
            this.generation = generation;
        }

        void apply(PlayerSnapshot snapshot, int generation) {
            long now = System.nanoTime();
            float progress = this.progress(now);
            this.fromPosition = this.renderPosition(progress);
            this.toPosition = new Vec3(snapshot.x(), snapshot.y(), snapshot.z());
            this.fromBodyYaw = this.renderBodyYaw(progress);
            this.toBodyYaw = snapshot.bodyYaw();
            this.fromHeadYaw = this.renderHeadYaw(progress);
            this.toHeadYaw = snapshot.headYaw();
            this.fromPitch = this.renderPitch(progress);
            this.toPitch = snapshot.pitch();
            this.applyFields(snapshot);
            this.applyVehicle(snapshot.vehicle(), true, now);
            this.snapshotNanos = now;
            this.generation = generation;
        }

        private void applyFields(PlayerSnapshot snapshot) {
            this.name = snapshot.name();
            this.sneaking = snapshot.sneaking();
            this.gliding = snapshot.gliding();
            this.swimming = snapshot.swimming();
            this.mainHand = snapshot.mainHand();
            this.offHand = snapshot.offHand();
            this.feet = snapshot.feet();
            this.legs = snapshot.legs();
            this.chest = snapshot.chest();
            this.head = snapshot.head();
        }

        private void applyVehicle(VehicleSnapshot vehicle, boolean interpolate, long now) {
            if (vehicle == null) {
                this.vehicleUuid = null;
                this.vehicleTypeId = null;
                this.fromVehiclePosition = null;
                this.toVehiclePosition = null;
                return;
            }
            Vec3 position = new Vec3(vehicle.x(), vehicle.y(), vehicle.z());
            boolean same = vehicle.uuid().equals(this.vehicleUuid)
                    && vehicle.entityTypeId().equals(this.vehicleTypeId);
            if (interpolate && same && this.toVehiclePosition != null) {
                float progress = this.progress(now);
                this.fromVehiclePosition = this.renderVehiclePosition(progress);
                this.fromVehicleYaw = this.renderVehicleYaw(progress);
                this.fromVehiclePitch = this.renderVehiclePitch(progress);
            } else {
                this.fromVehiclePosition = position;
                this.fromVehicleYaw = vehicle.yaw();
                this.fromVehiclePitch = vehicle.pitch();
            }
            this.vehicleUuid = vehicle.uuid();
            this.vehicleEntityId = vehicle.entityId();
            this.vehicleTypeId = vehicle.entityTypeId();
            this.toVehiclePosition = position;
            this.toVehicleYaw = vehicle.yaw();
            this.toVehiclePitch = vehicle.pitch();
        }

        UUID uuid() { return this.uuid; }
        String name() { return this.name; }
        boolean sneaking() { return this.sneaking; }
        boolean gliding() { return this.gliding; }
        boolean swimming() { return this.swimming; }
        ItemSnapshot mainHand() { return this.mainHand; }
        ItemSnapshot offHand() { return this.offHand; }
        ItemSnapshot feet() { return this.feet; }
        ItemSnapshot legs() { return this.legs; }
        ItemSnapshot chest() { return this.chest; }
        ItemSnapshot head() { return this.head; }
        int generation() { return this.generation; }
        boolean hasVehicle() { return this.vehicleUuid != null && this.toVehiclePosition != null; }
        UUID vehicleUuid() { return this.vehicleUuid; }
        int vehicleEntityId() { return this.vehicleEntityId; }
        String vehicleTypeId() { return this.vehicleTypeId; }

        Vec3 renderPosition(float progress) {
            return this.fromPosition.lerp(this.toPosition, progress);
        }

        double renderX(float progress) { return Mth.lerp(progress, this.fromPosition.x, this.toPosition.x); }
        double renderY(float progress) { return Mth.lerp(progress, this.fromPosition.y, this.toPosition.y); }
        double renderZ(float progress) { return Mth.lerp(progress, this.fromPosition.z, this.toPosition.z); }

        float renderBodyYaw(float progress) {
            return Mth.rotLerp(progress, this.fromBodyYaw, this.toBodyYaw);
        }

        float renderHeadYaw(float progress) {
            return Mth.rotLerp(progress, this.fromHeadYaw, this.toHeadYaw);
        }

        float renderPitch(float progress) {
            return Mth.lerp(progress, this.fromPitch, this.toPitch);
        }

        Vec3 renderVehiclePosition(float progress) {
            if (this.toVehiclePosition == null) {
                return Vec3.ZERO;
            }
            return this.fromVehiclePosition == null
                    ? this.toVehiclePosition
                    : this.fromVehiclePosition.lerp(this.toVehiclePosition, progress);
        }

        double renderVehicleX(float progress) {
            return this.fromVehiclePosition == null || this.toVehiclePosition == null
                    ? 0.0D : Mth.lerp(progress, this.fromVehiclePosition.x, this.toVehiclePosition.x);
        }

        double renderVehicleY(float progress) {
            return this.fromVehiclePosition == null || this.toVehiclePosition == null
                    ? 0.0D : Mth.lerp(progress, this.fromVehiclePosition.y, this.toVehiclePosition.y);
        }

        double renderVehicleZ(float progress) {
            return this.fromVehiclePosition == null || this.toVehiclePosition == null
                    ? 0.0D : Mth.lerp(progress, this.fromVehiclePosition.z, this.toVehiclePosition.z);
        }

        float renderVehicleYaw(float progress) {
            return Mth.rotLerp(progress, this.fromVehicleYaw, this.toVehicleYaw);
        }

        float renderVehiclePitch(float progress) {
            return Mth.lerp(progress, this.fromVehiclePitch, this.toVehiclePitch);
        }

        float progress(long now) {
            return Mth.clamp((float) (now - this.snapshotNanos) / INTERPOLATION_NANOS, 0.0F, 1.0F);
        }
    }
}
