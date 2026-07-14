package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;

/** Shared distances for the exclusive vanilla-terrain/Voxy circular handoff. */
public final class LodBoundaryFade {
    private static final float MIN_CACHE_OVERLAP = 16.0f;

    private LodBoundaryFade() {
    }

    public record Distances(float fadeStart, float fadeEnd, float maskDistance) {
        public boolean enabled() {
            return this.fadeEnd > this.fadeStart;
        }
    }

    public static Distances getDistances() {
        float vanillaDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f;
        VoxyConfig config = VoxyConfig.CONFIG;

        if (!config.enableLodBoundaryFade) {
            return new Distances(vanillaDistance, vanillaDistance, vanillaDistance);
        }

        float circleRadius = Math.max(0.0f, vanillaDistance - config.lodBoundaryOverdrawDistance);
        // Keep at least one complete chunk of LOD geometry ready beneath the
        // vanilla-owned side. It is cache/coverage only and is never allowed to
        // shade a pixel inside the circle.
        float cacheOverlap = Math.max(MIN_CACHE_OVERLAP, config.lodBoundaryFadeLength);
        float cacheStart = Math.max(0.0f, circleRadius - cacheOverlap);
        float maskDistance = Math.max(0.0f, cacheStart - config.lodBoundaryBuffer);
        return new Distances(cacheStart, circleRadius, maskDistance);
    }
}
