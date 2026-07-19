package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;

/** Camera-centred terrain ownership distances shared by all render paths. */
public final class LodBoundaryFade {
    private static final float MIN_VANILLA_RADIUS = 16.0f;
    private static final Distances DISABLED = new Distances(0.0f, 0.0f);

    // Config/render distance change very rarely. Cache the immutable result so
    // the several per-frame consumers do not allocate duplicate records.
    private static int cachedRenderDistance = Integer.MIN_VALUE;
    private static boolean cachedEnabled;
    private static int cachedLength;
    private static int cachedInset;
    private static int cachedBuffer;
    private static boolean cachedSubmerged;
    private static Distances cachedDistances = DISABLED;

    private LodBoundaryFade() {
    }

    public record Distances(float fadeStart, float fadeEnd) {
        public boolean enabled() {
            return this.fadeEnd > this.fadeStart;
        }
    }

    public static Distances getDistances() {
        VoxyConfig config = VoxyConfig.CONFIG;
        int renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance();
        boolean enabled = config.enableLodBoundaryFade;
        int length = config.lodBoundaryFadeLength;
        int inset = config.lodBoundaryInset;
        int buffer = config.lodBoundaryBuffer;
        // The circular ownership mask currently applies to opaque terrain. Water remains on the
        // normal translucent chunk-depth path, so keeping the mask active while the camera is in
        // a fluid lets opaque LOD replace the water column before its surface can be composited.
        // Fall back to Voxy's original chunk handoff underwater; this is a single camera-state read
        // per frame and avoids adding any world scans or fluid-specific draw passes.
        boolean submerged = Minecraft.getInstance().gameRenderer.getMainCamera().getFluidInCamera()
                != FogType.NONE;

        if (renderDistance == cachedRenderDistance
                && enabled == cachedEnabled
                && length == cachedLength
                && inset == cachedInset
                && buffer == cachedBuffer
                && submerged == cachedSubmerged) {
            return cachedDistances;
        }

        cachedRenderDistance = renderDistance;
        cachedEnabled = enabled;
        cachedLength = length;
        cachedInset = inset;
        cachedBuffer = buffer;
        cachedSubmerged = submerged;

        float vanillaDistance = renderDistance * 16.0f;
        if (!enabled || submerged) {
            return cachedDistances = new Distances(vanillaDistance, vanillaDistance);
        }

        float fadeEnd = Math.max(MIN_VANILLA_RADIUS,
                vanillaDistance - inset - buffer);
        float fadeWidth = Math.min(length, Math.max(0.0f, fadeEnd - MIN_VANILLA_RADIUS));
        if (fadeWidth < 1.0f) {
            return cachedDistances = new Distances(vanillaDistance, vanillaDistance);
        }

        return cachedDistances = new Distances(fadeEnd - fadeWidth, fadeEnd);
    }
}
