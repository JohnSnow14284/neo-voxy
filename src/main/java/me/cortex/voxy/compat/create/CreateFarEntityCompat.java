package me.cortex.voxy.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CreateFarEntityCompat {
    public static final int MIN_DISTANCE_CHUNKS = 8;
    public static final int MAX_DISTANCE_CHUNKS = 127;
    private static final int CONTRAPTION_TICKET_RADIUS = 2;
    private static final String TRAIN_PATH = "carriage_contraption";
    private static final Set<String> CHUNK_BOUND_ENTITY_PATHS = Set.of(
            "contraption",
            "stationary_contraption",
            "gantry_contraption"
    );
    private static final TicketType<UUID> CONTRAPTION_TICKET = TicketType.create(
            "voxy_create_contraption", UUID::compareTo
    );
    private static final Map<UUID, Settings> PLAYER_SETTINGS = new ConcurrentHashMap<>();
    private static final Map<UUID, ContraptionTicket> CONTRAPTION_TICKETS = new ConcurrentHashMap<>();

    private CreateFarEntityCompat() {
    }

    public static boolean isCreateDynamicEntity(EntityType<?> type) {
        return isCreateChunkBoundEntity(type) || isCreateTrainEntity(type);
    }

    public static boolean isCreateChunkBoundEntity(EntityType<?> type) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return id != null && "create".equals(id.getNamespace()) && CHUNK_BOUND_ENTITY_PATHS.contains(id.getPath());
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

    public static void tickContraptionTickets(MinecraftServer server) {
        if (!ModList.get().isLoaded("create") || !hasEnabledCreateSettings()) {
            releaseContraptionTickets(server);
            return;
        }
        Set<UUID> retained = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.isRemoved() || !isCreateChunkBoundEntity(entity.getType())
                        || !isContraptionWithinConfiguredDistance(level, entity)) {
                    continue;
                }
                retained.add(entity.getUUID());
                updateContraptionTicket(server, level, entity);
            }
        }

        for (Map.Entry<UUID, ContraptionTicket> entry : Set.copyOf(CONTRAPTION_TICKETS.entrySet())) {
            if (!retained.contains(entry.getKey()) && CONTRAPTION_TICKETS.remove(entry.getKey(), entry.getValue())) {
                removeContraptionTicket(server, entry.getKey(), entry.getValue());
            }
        }
    }

    public static void clearContraptionTickets(MinecraftServer server) {
        releaseContraptionTickets(server);
        PLAYER_SETTINGS.clear();
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

    private static boolean isContraptionWithinConfiguredDistance(ServerLevel level, Entity entity) {
        for (ServerPlayer player : level.players()) {
            Settings settings = PLAYER_SETTINGS.get(player.getUUID());
            if (settings == null || !settings.enabled()) {
                continue;
            }
            double distance = settings.contraptionDistance() * 16.0D;
            double dx = player.getX() - entity.getX();
            double dz = player.getZ() - entity.getZ();
            if (dx * dx + dz * dz <= distance * distance) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEnabledCreateSettings() {
        for (Settings settings : PLAYER_SETTINGS.values()) {
            if (settings.enabled()) {
                return true;
            }
        }
        return false;
    }

    private static void releaseContraptionTickets(MinecraftServer server) {
        for (Map.Entry<UUID, ContraptionTicket> entry : Set.copyOf(CONTRAPTION_TICKETS.entrySet())) {
            removeContraptionTicket(server, entry.getKey(), entry.getValue());
        }
        CONTRAPTION_TICKETS.clear();
    }

    private static void updateContraptionTicket(MinecraftServer server, ServerLevel level, Entity entity) {
        UUID entityId = entity.getUUID();
        Set<ChunkPos> chunks = new HashSet<>();
        chunks.add(entity.chunkPosition());
        if (entity instanceof CreateContraptionControllerAccessor accessor) {
            BlockPos controller = accessor.voxy$getControllerPos();
            if (controller != null) {
                chunks.add(new ChunkPos(controller));
            }
        }
        ContraptionTicket next = new ContraptionTicket(level.dimension(), Set.copyOf(chunks));
        ContraptionTicket previous = CONTRAPTION_TICKETS.put(entityId, next);
        if (next.equals(previous)) {
            return;
        }
        if (previous != null) {
            removeContraptionTicket(server, entityId, previous);
        }
        for (ChunkPos chunk : next.chunks()) {
            level.getChunkSource().addRegionTicket(
                    CONTRAPTION_TICKET, chunk, CONTRAPTION_TICKET_RADIUS, entityId
            );
        }
    }

    private static void removeContraptionTicket(MinecraftServer server, UUID entityId, ContraptionTicket ticket) {
        ServerLevel level = server.getLevel(ticket.dimension());
        if (level != null) {
            for (ChunkPos chunk : ticket.chunks()) {
                level.getChunkSource().removeRegionTicket(
                        CONTRAPTION_TICKET, chunk, CONTRAPTION_TICKET_RADIUS, entityId
                );
            }
        }
    }

    public static boolean isWithinDistance(double distanceSquared, int distanceChunks) {
        double distance = Math.clamp(distanceChunks,
                MIN_DISTANCE_CHUNKS, MAX_DISTANCE_CHUNKS) * 16.0D;
        return distanceSquared <= distance * distance;
    }

    private record Settings(boolean enabled, int contraptionDistance, int trainDistance) {
    }

    private record ContraptionTicket(ResourceKey<Level> dimension, Set<ChunkPos> chunks) {
    }
}
