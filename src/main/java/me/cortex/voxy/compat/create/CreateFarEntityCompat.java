package me.cortex.voxy.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CreateFarEntityCompat {
    public static final int MIN_DISTANCE_CHUNKS = 8;
    public static final int MAX_DISTANCE_CHUNKS = 127;
    private static final String TRAIN_PATH = "carriage_contraption";
    private static final Set<String> CHUNK_BOUND_ENTITY_PATHS = Set.of(
            "contraption",
            "stationary_contraption",
            "gantry_contraption"
    );
    private static final Map<UUID, Settings> PLAYER_SETTINGS = new ConcurrentHashMap<>();

    private CreateFarEntityCompat() {
    }

    public static boolean isCreateDynamicEntity(EntityType<?> type) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return id != null && "create".equals(id.getNamespace())
                && (CHUNK_BOUND_ENTITY_PATHS.contains(id.getPath()) || TRAIN_PATH.equals(id.getPath()));
    }

    public static boolean isCreateTrainEntity(EntityType<?> type) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return id != null && "create".equals(id.getNamespace()) && TRAIN_PATH.equals(id.getPath());
    }

    public static void updatePlayerSettings(UUID player, boolean enabled,
                                            int contraptionDistance, int trainDistance) {
        PLAYER_SETTINGS.put(player, new Settings(
                enabled,
                Math.clamp(contraptionDistance, MIN_DISTANCE_CHUNKS, MAX_DISTANCE_CHUNKS),
                Math.clamp(trainDistance, MIN_DISTANCE_CHUNKS, MAX_DISTANCE_CHUNKS)
        ));
    }

    public static void removePlayerSettings(UUID player) {
        PLAYER_SETTINGS.remove(player);
    }

    public static int getMaximumTrackingDistance(EntityType<?> type) {
        if (!isCreateDynamicEntity(type)) {
            return 0;
        }
        boolean train = isCreateTrainEntity(type);
        int maximum = 0;
        for (Settings settings : PLAYER_SETTINGS.values()) {
            if (settings.enabled()) {
                maximum = Math.max(maximum,
                        train ? settings.trainDistance() : settings.contraptionDistance());
            }
        }
        return maximum;
    }

    public static int getPlayerTrackingDistance(ServerPlayer player, EntityType<?> type) {
        Settings settings = PLAYER_SETTINGS.get(player.getUUID());
        if (settings == null || !settings.enabled() || !isCreateDynamicEntity(type)) {
            return 0;
        }
        return isCreateTrainEntity(type) ? settings.trainDistance() : settings.contraptionDistance();
    }

    public static boolean isTrainRenderActive(Level level, BlockPos position) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        for (ServerPlayer player : serverLevel.players()) {
            Settings settings = PLAYER_SETTINGS.get(player.getUUID());
            if (settings == null || !settings.enabled()) {
                continue;
            }
            double distance = settings.trainDistance() * 16.0D;
            if (player.distanceToSqr(position.getX() + 0.5D,
                    position.getY() + 0.5D, position.getZ() + 0.5D) <= distance * distance) {
                return true;
            }
        }
        return false;
    }

    public static boolean isWithinDistance(double distanceSquared, int distanceChunks) {
        double distance = Math.clamp(distanceChunks,
                MIN_DISTANCE_CHUNKS, MAX_DISTANCE_CHUNKS) * 16.0D;
        return distanceSquared <= distance * distance;
    }

    private record Settings(boolean enabled, int contraptionDistance, int trainDistance) {
    }
}
