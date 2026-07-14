package me.cortex.voxy.client.mixin.fakesight;

import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(IntegratedServer.class)
public abstract class MixinIntegratedServer {
    @Unique private static final int VOXY_MOVING_DISTANCE = 32;
    @Unique private static final int VOXY_STATIONARY_DELAY_TICKS = 80;
    @Unique private static final int VOXY_EXPANSION_INTERVAL_TICKS = 40;
    @Unique private static final int VOXY_EXPANSION_STEP = 8;

    @Unique private long voxy$lastPlayerChunk = Long.MIN_VALUE;
    @Unique private int voxy$lastDimensionHash;
    @Unique private int voxy$stationaryTicks;

    @ModifyArg(
            method = "tickServer",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Math;max(II)I",
                    ordinal = 0
            ),
            index = 1
    )
    private int voxy$modifyIntegratedServerRenderDistance(int originalDistance) {
        if ((!VoxyConfig.CONFIG.enableExtendedRequestDistance
                && !VoxyConfig.CONFIG.enableCreateFarEntityRendering)
                || !VoxyConfig.CONFIG.isRenderingEnabled()) {
            this.voxy$resetExtendedDistanceState();
            return originalDistance;
        }

        int requestedDistance = VoxyConfig.CONFIG.getEffectiveRequestDistance();
        int movingDistance = Math.min(requestedDistance, VOXY_MOVING_DISTANCE);
        var server = (IntegratedServer) (Object) this;
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            this.voxy$resetExtendedDistanceState();
            return movingDistance;
        }

        var player = players.getFirst();
        ChunkPos chunk = player.chunkPosition();
        long chunkKey = ChunkPos.asLong(chunk.x, chunk.z);
        int dimensionHash = player.serverLevel().dimension().hashCode();
        if (chunkKey != this.voxy$lastPlayerChunk || dimensionHash != this.voxy$lastDimensionHash) {
            this.voxy$lastPlayerChunk = chunkKey;
            this.voxy$lastDimensionHash = dimensionHash;
            this.voxy$stationaryTicks = 0;
            return movingDistance;
        }

        if (this.voxy$stationaryTicks < Integer.MAX_VALUE) {
            this.voxy$stationaryTicks++;
        }
        if (this.voxy$stationaryTicks < VOXY_STATIONARY_DELAY_TICKS) {
            return movingDistance;
        }

        int expansionSteps = 1 + (this.voxy$stationaryTicks - VOXY_STATIONARY_DELAY_TICKS)
                / VOXY_EXPANSION_INTERVAL_TICKS;
        int expandedDistance = movingDistance + expansionSteps * VOXY_EXPANSION_STEP;
        return Math.min(requestedDistance, expandedDistance);
    }

    @Unique
    private void voxy$resetExtendedDistanceState() {
        this.voxy$lastPlayerChunk = Long.MIN_VALUE;
        this.voxy$lastDimensionHash = 0;
        this.voxy$stationaryTicks = 0;
    }
}
