package me.cortex.voxy.client.compat.sable;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.compat.sable.SableContraptionRenderDistance;

//Client-side view of the sable contraption render distance. Every consumer reads it per frame, so a
//config change takes effect on the next one - nothing here caches or needs rebuilding.
public final class SableClientRenderDistance {
    private static final int BLOCKS_PER_CHUNK = 16;

    private SableClientRenderDistance() {
    }

    public static int extendVanillaRenderDistanceChunks(int vanillaRenderDistanceChunks) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled() || !VoxyConfig.CONFIG.sableLodRendering) {
            return vanillaRenderDistanceChunks;
        }

        return SableContraptionRenderDistance.extendVanillaRenderDistanceChunks(
                vanillaRenderDistanceChunks,
                VoxyConfig.CONFIG.sectionRenderDistance,
                VoxyConfig.CONFIG.simulatedContraptionRenderDistancePercent
        );
    }

    public static double getRenderDistanceBlocks(int vanillaRenderDistanceChunks) {
        return extendVanillaRenderDistanceChunks(vanillaRenderDistanceChunks) * (double) BLOCKS_PER_CHUNK;
    }

    public static boolean isVoxyRenderDistanceActive() {
        return VoxyConfig.CONFIG.isRenderingEnabled() && VoxyConfig.CONFIG.sableLodRendering
                && VoxyConfig.CONFIG.simulatedContraptionRenderDistancePercent > 0;
    }
}
