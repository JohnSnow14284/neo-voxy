package me.cortex.voxy.commonImpl.compat.sable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.mixin.sable.SableSubLevelHoldingChunkMapAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;

public final class SableLodChunkManager {
    private static final TicketType<ChunkPos> VOXY_SABLE_LOD_TICKET = TicketType.create("voxy_sable_lod", Comparator.comparingLong(ChunkPos::toLong));
    private static final int TICKET_DISTANCE = 2;

    private static final Map<ServerLevel, LongSet> activeChunkLoads = new WeakHashMap<>();

    private static boolean sableUnavailable;

    private SableLodChunkManager() {
    }

    public static void updateTickets(ServerLevel level, LongSet trackedChunks, LongSet trackedHoldingChunks) {
        if (sableUnavailable) {
            clearTickets(level, trackedChunks, trackedHoldingChunks);
            return;
        }

        try {
            double horizontalRenderDistanceBlocks = SableContraptionRenderDistance.getRangeBlocks(level);
            if (horizontalRenderDistanceBlocks <= 0.0) {
                clearTickets(level, trackedChunks, trackedHoldingChunks);
                return;
            }

            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                clearTickets(level, trackedChunks, trackedHoldingChunks);
                return;
            }

            if (level.players().isEmpty()) {
                clearTickets(level, trackedChunks, trackedHoldingChunks);
                return;
            }

            LongSet desiredChunks = new LongOpenHashSet();
            LongSet desiredHoldingChunks = new LongOpenHashSet();
            double maxHorizontalDistanceSquared = horizontalRenderDistanceBlocks * horizontalRenderDistanceBlocks;

            for (ServerSubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel.isRemoved()) {
                    continue;
                }

                BoundingBox3dc bounds = subLevel.boundingBox();
                if (bounds == null || !isWithinHorizontalDistance(level, bounds, maxHorizontalDistanceSquared)) {
                    continue;
                }

                addChunkBounds(level, bounds, desiredChunks, maxHorizontalDistanceSquared);
            }

            updateHoldingChunkLoads(level, container.getHoldingChunkMap(), desiredChunks, desiredHoldingChunks, trackedHoldingChunks, maxHorizontalDistanceSquared);
            removeStaleTickets(level, trackedChunks, desiredChunks);
            addMissingTickets(level, trackedChunks, desiredChunks);
            activeChunkLoads.put(level, new LongOpenHashSet(desiredChunks));
        } catch (NoClassDefFoundError e) {
            sableUnavailable = true;
            clearTickets(level, trackedChunks, trackedHoldingChunks);
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Voxy Sable LOD compatibility after direct access failed", e);
            sableUnavailable = true;
            clearTickets(level, trackedChunks, trackedHoldingChunks);
        }
    }

    public static void clearTickets(ServerLevel level, LongSet trackedChunks, LongSet trackedHoldingChunks) {
        activeChunkLoads.remove(level);
        clearTickets(level, trackedChunks);
        clearHoldingChunkLoads(level, trackedHoldingChunks);
    }

    public static void clearTickets(ServerLevel level, LongSet trackedChunks) {
        activeChunkLoads.remove(level);
        if (trackedChunks.isEmpty()) {
            return;
        }

        LongIterator iterator = trackedChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            ChunkPos chunkPos = new ChunkPos(chunk);
            level.getChunkSource().removeRegionTicket(VOXY_SABLE_LOD_TICKET, chunkPos, TICKET_DISTANCE, chunkPos);
            iterator.remove();
        }
    }

    public static boolean shouldTreatChunkAsLoaded(ServerLevel level, int chunkX, int chunkZ) {
        if (sableUnavailable) {
            return false;
        }

        double horizontalRenderDistanceBlocks = SableContraptionRenderDistance.getRangeBlocks(level);
        if (horizontalRenderDistanceBlocks <= 0.0) {
            return false;
        }

        long chunk = ChunkPos.asLong(chunkX, chunkZ);
        LongSet activeChunks = activeChunkLoads.get(level);
        if (activeChunks != null && activeChunks.contains(chunk)) {
            return true;
        }

        return isChunkWithinHorizontalDistance(level, new ChunkPos(chunkX, chunkZ), horizontalRenderDistanceBlocks * horizontalRenderDistanceBlocks);
    }

    public static boolean isSubLevelAlreadyActive(ServerLevel level, SubLevelData data) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return false;
        }

        SubLevel byUuid = container.getSubLevel(data.uuid());
        if (byUuid != null && !byUuid.isRemoved()) {
            return true;
        }

        CompoundTag fullTag = data.fullTag();
        if (fullTag == null || !fullTag.contains("plot")) {
            return false;
        }

        CompoundTag plotTag = fullTag.getCompound("plot");
        if (!plotTag.contains("plot_x") || !plotTag.contains("plot_z")) {
            return false;
        }

        SubLevel byPlot = container.getSubLevel(plotTag.getInt("plot_x"), plotTag.getInt("plot_z"));
        return byPlot != null && !byPlot.isRemoved();
    }

    private static void updateHoldingChunkLoads(
            ServerLevel level,
            SubLevelHoldingChunkMap holdingChunkMap,
            LongSet desiredChunks,
            LongSet desiredHoldingChunks,
            LongSet trackedHoldingChunks,
            double maxHorizontalDistanceSquared) {
        if (holdingChunkMap == null) {
            trackedHoldingChunks.clear();
            return;
        }

        LongSet knownHoldingChunks = getKnownHoldingChunks(level, holdingChunkMap);
        Long2ObjectMap<SubLevelHoldingChunk> loadedHoldingChunks = ((SableSubLevelHoldingChunkMapAccessor) holdingChunkMap).voxy$getLoadedHoldingChunks();
        LongIterator iterator = knownHoldingChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            ChunkPos chunkPos = new ChunkPos(chunk);
            if (!isChunkWithinHorizontalDistance(level, chunkPos, maxHorizontalDistanceSquared)) {
                continue;
            }

            desiredHoldingChunks.add(chunk);
            trackedHoldingChunks.add(chunk);
            holdingChunkMap.updateChunkStatus(chunkPos, true);

            SubLevelHoldingChunk holdingChunk = loadedHoldingChunks == null ? null : loadedHoldingChunks.get(chunk);
            if (holdingChunk == null) {
                continue;
            }

            for (HoldingSubLevel holdingSubLevel : holdingChunk.getLoadedHoldingSubLevels()) {
                BoundingBox3dc bounds = holdingSubLevel.data().bounds();
                if (bounds != null && isWithinHorizontalDistance(level, bounds, maxHorizontalDistanceSquared)) {
                    addChunkBounds(level, bounds, desiredChunks, maxHorizontalDistanceSquared);
                }
            }
        }

        removeStaleHoldingChunkLoads(level, holdingChunkMap, trackedHoldingChunks, desiredHoldingChunks);
    }

    private static LongSet getKnownHoldingChunks(ServerLevel level, SubLevelHoldingChunkMap holdingChunkMap) {
        LongSet chunks = SableHoldingChunkIndexSavedData.getOrLoad(level).copyChunks();

        Long2ObjectMap<SubLevelHoldingChunk> loadedHoldingChunks = ((SableSubLevelHoldingChunkMapAccessor) holdingChunkMap).voxy$getLoadedHoldingChunks();
        if (loadedHoldingChunks != null) {
            chunks.addAll(loadedHoldingChunks.keySet());
        }

        return chunks;
    }

    private static void removeStaleHoldingChunkLoads(ServerLevel level, SubLevelHoldingChunkMap holdingChunkMap, LongSet trackedHoldingChunks, LongSet desiredHoldingChunks) {
        LongIterator iterator = trackedHoldingChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (desiredHoldingChunks.contains(chunk)) {
                continue;
            }

            ChunkPos chunkPos = new ChunkPos(chunk);
            if (PhysicsChunkTicketManager.isChunkLoadedEnough(level, chunkPos.x, chunkPos.z)) {
                continue;
            }

            holdingChunkMap.updateChunkStatus(chunkPos, false);
            iterator.remove();
        }
    }

    private static void clearHoldingChunkLoads(ServerLevel level, LongSet trackedHoldingChunks) {
        if (trackedHoldingChunks.isEmpty()) {
            return;
        }

        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            SubLevelHoldingChunkMap holdingChunkMap = container == null ? null : container.getHoldingChunkMap();
            if (holdingChunkMap != null) {
                LongIterator iterator = trackedHoldingChunks.iterator();
                while (iterator.hasNext()) {
                    ChunkPos chunkPos = new ChunkPos(iterator.nextLong());
                    //Only hand back the chunks we were artificially holding. Reporting one that vanilla
                    //still has loaded as gone makes sable serialise and remove every sub-level standing in
                    //it, and it is only restored on the next FullChunkStatus transition - a ship right next
                    //to the player would disappear until its chunk happens to cycle. Same guard as
                    //removeStaleHoldingChunkLoads.
                    if (PhysicsChunkTicketManager.isChunkLoadedEnough(level, chunkPos.x, chunkPos.z)) {
                        continue;
                    }
                    holdingChunkMap.updateChunkStatus(chunkPos, false);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            Logger.warn("Failed to release Sable holding chunk loads", e);
        } finally {
            trackedHoldingChunks.clear();
        }
    }

    private static void addMissingTickets(ServerLevel level, LongSet trackedChunks, LongSet desiredChunks) {
        LongIterator iterator = desiredChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (trackedChunks.add(chunk)) {
                ChunkPos chunkPos = new ChunkPos(chunk);
                level.getChunkSource().addRegionTicket(VOXY_SABLE_LOD_TICKET, chunkPos, TICKET_DISTANCE, chunkPos);
            }
        }
    }

    private static void removeStaleTickets(ServerLevel level, LongSet trackedChunks, LongSet desiredChunks) {
        LongIterator iterator = trackedChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (!desiredChunks.contains(chunk)) {
                ChunkPos chunkPos = new ChunkPos(chunk);
                level.getChunkSource().removeRegionTicket(VOXY_SABLE_LOD_TICKET, chunkPos, TICKET_DISTANCE, chunkPos);
                iterator.remove();
            }
        }
    }

    private static void addChunkBounds(ServerLevel level, BoundingBox3dc bounds, LongSet desiredChunks, double maxHorizontalDistanceSquared) {
        int minChunkX = Mth.floor(bounds.minX()) >> 4;
        int maxChunkX = Mth.floor(bounds.maxX()) >> 4;
        int minChunkZ = Mth.floor(bounds.minZ()) >> 4;
        int maxChunkZ = Mth.floor(bounds.maxZ()) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
                if (isChunkWithinHorizontalDistance(level, chunkPos, maxHorizontalDistanceSquared)) {
                    desiredChunks.add(chunkPos.toLong());
                }
            }
        }
    }

    private static boolean isWithinHorizontalDistance(ServerLevel level, BoundingBox3dc bounds, double maxHorizontalDistanceSquared) {
        double minX = bounds.minX();
        double maxX = bounds.maxX();
        double minZ = bounds.minZ();
        double maxZ = bounds.maxZ();

        for (var player : level.players()) {
            double dx = distanceToRange(player.getX(), minX, maxX);
            double dz = distanceToRange(player.getZ(), minZ, maxZ);
            if ((dx * dx) + (dz * dz) <= maxHorizontalDistanceSquared) {
                return true;
            }
        }

        return false;
    }

    private static boolean isChunkWithinHorizontalDistance(ServerLevel level, ChunkPos chunkPos, double maxHorizontalDistanceSquared) {
        double minX = chunkPos.getMinBlockX();
        double maxX = chunkPos.getMaxBlockX() + 1.0;
        double minZ = chunkPos.getMinBlockZ();
        double maxZ = chunkPos.getMaxBlockZ() + 1.0;

        for (var player : level.players()) {
            double dx = distanceToRange(player.getX(), minX, maxX);
            double dz = distanceToRange(player.getZ(), minZ, maxZ);
            if ((dx * dx) + (dz * dz) <= maxHorizontalDistanceSquared) {
                return true;
            }
        }

        return false;
    }

    private static double distanceToRange(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0;
    }

}
