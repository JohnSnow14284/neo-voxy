package me.cortex.voxy.compat.far.create;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.compat.far.FarContraptionProvider;
import me.cortex.voxy.compat.far.FarEntityProtocol.ContraptionSnapshot;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Server-side bridge to Create. It deliberately lives in a separate package so
 * none of its Create classes are resolved when the optional mod is absent.
 */
public final class CreateFarContraptionProvider implements FarContraptionProvider {
    private static final Field SERIALISED_ENTITY = findSerialisedEntityField();
    private static final long DEFINITION_CACHE_TTL_NANOS = TimeUnit.MINUTES.toNanos(5L);
    private final Map<UUID, CachedDefinition> definitionCache = new LinkedHashMap<>();

    @Override
    public List<ContraptionSnapshot> collect(ServerPlayer viewer, int maximumDistance) {
        long now = System.nanoTime();
        this.definitionCache.entrySet().removeIf(entry -> now - entry.getValue().lastSeenNanos > DEFINITION_CACHE_TTL_NANOS);
        ServerLevel level = viewer.serverLevel();
        ResourceKey<Level> dimension = level.dimension();
        double maximumDistanceSquared = (double) maximumDistance * maximumDistance;
        Map<UUID, ContraptionSnapshot> snapshots = new LinkedHashMap<>();

        // Ordinary moving structures only exist while their server chunk is loaded.
        // They are still useful here because Create's own tracking range is much
        // shorter than Voxy's LOD distance.
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof AbstractContraptionEntity contraptionEntity)
                    || contraptionEntity.getContraption() == null
                    || !contraptionEntity.isAliveOrStale()) {
                continue;
            }
            if (viewer.distanceToSqr(entity) > maximumDistanceSquared) {
                continue;
            }
            if (contraptionEntity instanceof CarriageContraptionEntity carriageEntity
                    && carriageEntity.getCarriage() != null
                    && carriageEntity.getCarriage().train != null) {
                Train train = carriageEntity.getCarriage().train;
                int index = train.carriages.indexOf(carriageEntity.getCarriage());
                if (index >= 0) {
                    UUID id = carriageId(train.id, index);
                    snapshots.put(id, this.snapshotLoaded(id, train.id, index, carriageEntity, now));
                    continue;
                }
            }
            snapshots.put(entity.getUUID(), this.snapshotLoaded(entity.getUUID(), null, -1, contraptionEntity, now));
        }

        // Trains continue moving in Create's global railway graph when their chunks
        // are not loaded and therefore have no entity. Rehydrate their saved
        // contraption definition and pair it with the live abstract carriage pose.
        for (Train train : Create.RAILWAYS.trains.values()) {
            for (int index = 0; index < train.carriages.size(); index++) {
                Carriage carriage = train.carriages.get(index);
                Carriage.DimensionalCarriageEntity dimensional = carriage.getDimensionalIfPresent(dimension);
                if (dimensional == null || dimensional.positionAnchor == null
                        || dimensional.rotationAnchors.either(v -> v == null)) {
                    continue;
                }
                UUID id = carriageId(train.id, index);
                CarriageContraptionEntity live = dimensional.entity.get();
                if (live != null && live.isAlive() && live.getContraption() != null) {
                    if (viewer.distanceToSqr(live) <= maximumDistanceSquared) {
                        snapshots.put(id, this.snapshotLoaded(id, train.id, index, live, now));
                    }
                    continue;
                }

                Vec3 position = dimensional.positionAnchor;
                if (viewer.position().distanceToSqr(position) > maximumDistanceSquared) {
                    continue;
                }
                CompoundTag savedDefinition = savedCarriageDefinition(carriage);
                if (savedDefinition == null || !savedDefinition.contains("Contraption")) {
                    continue;
                }
                int signature = savedDefinition.getCompound("Contraption").hashCode();
                CachedDefinition cachedDefinition = this.cachedSavedDefinition(id, savedDefinition, signature, now);
                CompoundTag definition = cachedDefinition.definition;
                Vec3 leading = dimensional.rotationAnchors.getFirst();
                Vec3 trailing = dimensional.rotationAnchors.getSecond();
                double dx = leading.x - trailing.x;
                double dy = leading.y - trailing.y;
                double dz = leading.z - trailing.z;
                float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) + 180.0F;
                float pitch = (float) (Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * -180.0 / Math.PI);
                snapshots.put(id, new ContraptionSnapshot(id, -1, train.id, index,
                        position.x, position.y, position.z, yaw, pitch, 0.0F, -1,
                        cachedDefinition.definitionHash, definition));
            }
        }

        return new ArrayList<>(snapshots.values());
    }

    private ContraptionSnapshot snapshotLoaded(UUID id, UUID trainId, int carriageIndex,
                                                AbstractContraptionEntity entity, long now) {
        int signature = 31 * System.identityHashCode(entity.getContraption())
                + entity.getContraption().getBlocks().hashCode();
        if (entity instanceof OrientedContraptionEntity oriented) {
            signature = 31 * signature + oriented.getInitialOrientation().ordinal();
        } else if (entity instanceof ControlledContraptionEntity controlled
                && controlled.getRotationAxis() != null) {
            signature = 31 * signature + controlled.getRotationAxis().ordinal();
        }

        CachedDefinition cached = this.definitionCache.get(id);
        if (cached != null && cached.signature == signature) {
            cached.lastSeenNanos = now;
        } else {
            cached = new CachedDefinition(signature, createLoadedDefinition(id, entity), now);
            this.definitionCache.put(id, cached);
        }

        float yaw = 0.0F;
        float pitch = 0.0F;
        float angle = 0.0F;
        int axis = -1;
        if (entity instanceof OrientedContraptionEntity oriented) {
            yaw = oriented.yaw;
            pitch = oriented.pitch;
        } else if (entity instanceof ControlledContraptionEntity controlled) {
            angle = controlled.getAngle(1.0F);
            if (controlled.getRotationAxis() != null) axis = controlled.getRotationAxis().ordinal();
        }

        return new ContraptionSnapshot(id, entity.getId(), trainId, carriageIndex,
                entity.getX(), entity.getY(), entity.getZ(), yaw, pitch, angle, axis,
                cached.definitionHash, cached.definition);
    }

    private static CompoundTag createLoadedDefinition(UUID id, AbstractContraptionEntity entity) {
        CompoundTag definition = new CompoundTag();
        definition.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        definition.putBoolean("Initialized", true);
        definition.putBoolean("Stalled", entity.isStalled());
        definition.put("Contraption", entity.getContraption().writeNBT(entity.registryAccess(), true));

        if (entity instanceof OrientedContraptionEntity oriented) {
            Direction initial = oriented.getInitialOrientation();
            if (initial != null) {
                definition.putString("InitialOrientation", initial.name());
            }
        } else if (entity instanceof ControlledContraptionEntity controlled) {
            if (controlled.getRotationAxis() != null) {
                definition.putString("Axis", controlled.getRotationAxis().name());
            }
        } else if (entity instanceof GantryContraptionEntity) {
            // The field is only needed by ticking logic. Far proxies never tick, but
            // readAdditional expects a valid enum value.
            definition.putString("GantryAxis", Direction.UP.name());
        }

        prepareStableDefinition(definition, id);
        return definition;
    }

    private CachedDefinition cachedSavedDefinition(UUID id, CompoundTag source, int signature, long now) {
        CachedDefinition cached = this.definitionCache.get(id);
        if (cached != null && cached.signature == signature) {
            cached.lastSeenNanos = now;
            return cached;
        }
        CompoundTag definition = source.copy();
        prepareStableDefinition(definition, id);
        cached = new CachedDefinition(signature, definition, now);
        this.definitionCache.put(id, cached);
        return cached;
    }

    private static void prepareStableDefinition(CompoundTag definition, UUID id) {
        definition.putUUID("UUID", id);
        definition.put("Pos", doubles(0.0D, 0.0D, 0.0D));
        definition.put("Motion", doubles(0.0D, 0.0D, 0.0D));
        definition.put("Rotation", floats(0.0F, 0.0F));
        // These are continuously supplied by the compact pose snapshot and must not
        // make the structure hash change every update.
        definition.remove("Yaw");
        definition.remove("Pitch");
        definition.remove("Angle");
        definition.remove("ForceYaw");
    }

    private static int definitionHash(CompoundTag definition) {
        int hash = definition.getString("id").hashCode();
        hash = 31 * hash + definition.getCompound("Contraption").hashCode();
        hash = 31 * hash + definition.getString("InitialOrientation").hashCode();
        hash = 31 * hash + definition.getString("Axis").hashCode();
        return hash;
    }

    private static CompoundTag savedCarriageDefinition(Carriage carriage) {
        if (SERIALISED_ENTITY == null) {
            return null;
        }
        try {
            CompoundTag tag = (CompoundTag) SERIALISED_ENTITY.get(carriage);
            return tag;
        } catch (IllegalAccessException e) {
            Logger.error("Could not read Create's saved carriage contraption", e);
            return null;
        }
    }

    private static Field findSerialisedEntityField() {
        try {
            Field field = Carriage.class.getDeclaredField("serialisedEntity");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Logger.error("Create carriage layout is not compatible with far train rendering", e);
            return null;
        }
    }

    private static UUID carriageId(UUID trainId, int index) {
        return UUID.nameUUIDFromBytes(("voxy:create-carriage:" + trainId + ':' + index)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static ListTag doubles(double... values) {
        ListTag tag = new ListTag();
        for (double value : values) tag.add(DoubleTag.valueOf(value));
        return tag;
    }

    private static ListTag floats(float... values) {
        ListTag tag = new ListTag();
        for (float value : values) tag.add(FloatTag.valueOf(value));
        return tag;
    }

    private static final class CachedDefinition {
        private final int signature;
        private final CompoundTag definition;
        private final int definitionHash;
        private long lastSeenNanos;

        private CachedDefinition(int signature, CompoundTag definition, long lastSeenNanos) {
            this.signature = signature;
            this.definition = definition;
            this.definitionHash = definitionHash(definition);
            this.lastSeenNanos = lastSeenNanos;
        }
    }
}
