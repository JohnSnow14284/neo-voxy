package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;

/** Camera-centred ownership distances shared by the depth mask and chunk bounds. */
public final class LodBoundaryFade {
    private static final float MIN_VANILLA_RADIUS = 16.0f;

    private LodBoundaryFade() {
    }

    public record Distances(float fadeStart, float fadeEnd) {
        public boolean enabled() {
            return this.fadeEnd > this.fadeStart;
        }
    }

    public static Distances getDistances() {
        float vanillaDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f;
        VoxyConfig config = VoxyConfig.CONFIG;
        if (!config.enableLodBoundaryFade) {
            return new Distances(vanillaDistance, vanillaDistance);
        }

        float fadeEnd = Math.max(MIN_VANILLA_RADIUS,
                vanillaDistance - config.lodBoundaryInset - config.lodBoundaryBuffer);
        float availableWidth = Math.max(0.0f, fadeEnd - MIN_VANILLA_RADIUS);
        float fadeWidth = Math.min(config.lodBoundaryFadeLength, availableWidth);
        if (fadeWidth < 1.0f) {
            return new Distances(vanillaDistance, vanillaDistance);
        }

        float fadeStart = fadeEnd - fadeWidth;
        return new Distances(fadeStart, fadeEnd);
    }

}
