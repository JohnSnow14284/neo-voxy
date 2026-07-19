package me.cortex.voxy.commonImpl.compat.sable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import me.cortex.voxy.common.Logger;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.joml.Vector3dc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

public final class SableParentChunkLightSync {
    private static final long REFRESH_INTERVAL_TICKS = 40L;
    private static final int PARENT_CHUNK_PADDING = 1;

    private static final Map<ServerLevel, Map<TrackingKey, Long>> NEXT_REFRESH_TICK = new WeakHashMap<>();
    private static boolean unavailable;

    private SableParentChunkLightSync() {
    }

    public static void tick(ServerLevel level) {
        if (unavailable || level.players().isEmpty()) {
            return;
        }

        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                NEXT_REFRESH_TICK.remove(level);
                return;
            }

            long gameTime = level.getGameTime();
            Map<TrackingKey, Long> nextRefreshTick = NEXT_REFRESH_TICK.computeIfAbsent(level, ignored -> new HashMap<>());
            Set<TrackingKey> activeKeys = new HashSet<>();

            for (ServerSubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel.isRemoved() || subLevel.getTrackingPlayers().isEmpty()) {
                    continue;
                }

                UUID subLevelId = subLevel.getUniqueId();
                if (subLevelId == null) {
                    continue;
                }

                for (UUID playerId : subLevel.getTrackingPlayers()) {
                    if (!(level.getPlayerByUUID(playerId) instanceof ServerPlayer player) || player.level() != level) {
                        continue;
                    }

                    TrackingKey key = new TrackingKey(playerId, subLevelId);
                    activeKeys.add(key);
                    long nextTick = nextRefreshTick.getOrDefault(key, Long.MIN_VALUE);
                    if (gameTime < nextTick) {
                        continue;
                    }

                    if (sendParentWorldChunks(level, player, subLevel.boundingBox(), subLevel.logicalPose().position())) {
                        nextRefreshTick.put(key, gameTime + REFRESH_INTERVAL_TICKS);
                    }
                }
            }

            nextRefreshTick.keySet().removeIf(key -> !activeKeys.contains(key));
            if (nextRefreshTick.isEmpty()) {
                NEXT_REFRESH_TICK.remove(level);
            }
        } catch (NoClassDefFoundError e) {
            unavailable = true;
            NEXT_REFRESH_TICK.clear();
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Voxy Sable parent chunk light sync after direct access failed", e);
            unavailable = true;
            NEXT_REFRESH_TICK.clear();
        }
    }

    private static boolean sendParentWorldChunks(ServerLevel level, ServerPlayer player, BoundingBox3dc bounds, Vector3dc position) {
        ChunkTrackingView trackingView = player.getChunkTrackingView();
        if (trackingView == null) {
            trackingView = ChunkTrackingView.EMPTY;
        }

        int minChunkX;
        int maxChunkX;
        int minChunkZ;
        int maxChunkZ;
        if (bounds == null) {
            int chunkX = Mth.floor(position.x()) >> 4;
            int chunkZ = Mth.floor(position.z()) >> 4;
            minChunkX = chunkX - PARENT_CHUNK_PADDING;
            maxChunkX = chunkX + PARENT_CHUNK_PADDING;
            minChunkZ = chunkZ - PARENT_CHUNK_PADDING;
            maxChunkZ = chunkZ + PARENT_CHUNK_PADDING;
        } else {
            minChunkX = (Mth.floor(bounds.minX()) >> 4) - PARENT_CHUNK_PADDING;
            maxChunkX = (Mth.floor(bounds.maxX()) >> 4) + PARENT_CHUNK_PADDING;
            minChunkZ = (Mth.floor(bounds.minZ()) >> 4) - PARENT_CHUNK_PADDING;
            maxChunkZ = (Mth.floor(bounds.maxZ()) >> 4) + PARENT_CHUNK_PADDING;
        }

        boolean sentAny = false;
        LevelLightEngine lightEngine = level.getLightEngine();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (trackingView.contains(chunkX, chunkZ)) {
                    continue;
                }

                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }

                player.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null));
                sentAny = true;
            }
        }

        return sentAny;
    }

    private record TrackingKey(UUID playerId, UUID subLevelId) {
    }
}
