package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;

/** Shared transition distances for the vanilla-terrain/Voxy overlap band. */
public final class LodBoundaryFade {
    private static final float CHUNK_SAFETY_MARGIN = 16.0f;

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

        if (!config.enableLodBoundaryFade || config.lodBoundaryFadeLength <= 0) {
            return new Distances(vanillaDistance, vanillaDistance, vanillaDistance);
        }

        float fadeEnd = Math.max(0.0f, vanillaDistance - config.lodBoundaryOverdrawDistance);
        float fadeStart = Math.max(0.0f, fadeEnd - config.lodBoundaryFadeLength);
        // Do not let a whole-section depth bound cross into the fade band. The
        // copied vanilla depth/stencil still resolves real terrain in this overlap.
        float maskDistance = Math.max(0.0f,
                fadeStart - CHUNK_SAFETY_MARGIN - config.lodBoundaryBuffer);
        return new Distances(fadeStart, fadeEnd, maskDistance);
    }
}
