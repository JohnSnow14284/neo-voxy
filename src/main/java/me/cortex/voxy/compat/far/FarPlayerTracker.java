package me.cortex.voxy.compat.far;

import me.cortex.voxy.compat.far.FarEntityProtocol.ItemSnapshot;
import me.cortex.voxy.compat.far.FarEntityProtocol.PlayerBatch;
import me.cortex.voxy.compat.far.FarEntityProtocol.PlayerSnapshot;
import me.cortex.voxy.compat.far.FarEntityProtocol.VehicleSnapshot;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class FarPlayerTracker {
    private final Map<UUID, TrackedPlayer> players = new ConcurrentHashMap<>();
    private volatile String dimensionKey = "";
    private volatile int generation;

    void clear() {
        this.players.clear();
        this.dimensionKey = "";
        this.generation = 0;
    }

    void apply(PlayerBatch batch) {
        int nextGeneration = this.generation + 1;
        this.generation = nextGeneration;
        this.dimensionKey = batch.dimensionKey();
        for (PlayerSnapshot snapshot : batch.players()) {
            this.players.compute(snapshot.uuid(), (uuid, current) -> {
                if (current == null) {
                    return new TrackedPlayer(snapshot, nextGeneration);
                }
                current.apply(snapshot, nextGeneration);
                return current;
            });
        }
        this.players.entrySet().removeIf(entry -> entry.getValue().generation() != nextGeneration);
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
            this.snapshotNanos = System.nanoTime();
            this.applyFields(snapshot);
            this.applyVehicle(snapshot.vehicle(), false);
            this.generation = generation;
        }

        void apply(PlayerSnapshot snapshot, int generation) {
            long now = System.nanoTime();
            this.fromPosition = this.renderPosition(now);
            this.toPosition = new Vec3(snapshot.x(), snapshot.y(), snapshot.z());
            this.fromBodyYaw = this.renderBodyYaw(now);
            this.toBodyYaw = snapshot.bodyYaw();
            this.fromHeadYaw = this.renderHeadYaw(now);
            this.toHeadYaw = snapshot.headYaw();
            this.fromPitch = this.renderPitch(now);
            this.toPitch = snapshot.pitch();
            this.applyFields(snapshot);
            this.applyVehicle(snapshot.vehicle(), true);
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

        private void applyVehicle(VehicleSnapshot vehicle, boolean interpolate) {
            if (vehicle == null) {
                this.vehicleUuid = null;
                this.vehicleTypeId = null;
                this.fromVehiclePosition = null;
                this.toVehiclePosition = null;
                return;
            }
            long now = System.nanoTime();
            Vec3 position = new Vec3(vehicle.x(), vehicle.y(), vehicle.z());
            boolean same = vehicle.uuid().equals(this.vehicleUuid)
                    && vehicle.entityTypeId().equals(this.vehicleTypeId);
            if (interpolate && same && this.toVehiclePosition != null) {
                this.fromVehiclePosition = this.renderVehiclePosition(now);
                this.fromVehicleYaw = this.renderVehicleYaw(now);
                this.fromVehiclePitch = this.renderVehiclePitch(now);
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

        Vec3 renderPosition(long now) {
            return this.fromPosition.lerp(this.toPosition, this.progress(now));
        }

        float renderBodyYaw(long now) {
            return Mth.rotLerp(this.progress(now), this.fromBodyYaw, this.toBodyYaw);
        }

        float renderHeadYaw(long now) {
            return Mth.rotLerp(this.progress(now), this.fromHeadYaw, this.toHeadYaw);
        }

        float renderPitch(long now) {
            return Mth.lerp(this.progress(now), this.fromPitch, this.toPitch);
        }

        Vec3 renderVehiclePosition(long now) {
            if (this.toVehiclePosition == null) {
                return Vec3.ZERO;
            }
            return this.fromVehiclePosition == null
                    ? this.toVehiclePosition
                    : this.fromVehiclePosition.lerp(this.toVehiclePosition, this.progress(now));
        }

        float renderVehicleYaw(long now) {
            return Mth.rotLerp(this.progress(now), this.fromVehicleYaw, this.toVehicleYaw);
        }

        float renderVehiclePitch(long now) {
            return Mth.lerp(this.progress(now), this.fromVehiclePitch, this.toVehiclePitch);
        }

        private float progress(long now) {
            return Mth.clamp((float) (now - this.snapshotNanos) / INTERPOLATION_NANOS, 0.0F, 1.0F);
        }
    }
}
