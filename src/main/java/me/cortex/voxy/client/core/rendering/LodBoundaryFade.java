package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;

/** Camera-centred ownership distances shared by the depth mask and LOD shaders. */
public final class LodBoundaryFade {
   private static final float MIN_VANILLA_RADIUS = 16.0F;

   private LodBoundaryFade() {
   }

   public record Distances(float fadeStart, float fadeEnd) {
      public boolean enabled() {
         return this.fadeEnd > this.fadeStart;
      }
   }

   public static Distances getDistances() {
      float vanillaDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0F;
      VoxyConfig config = VoxyConfig.CONFIG;
      if (!config.enableLodBoundaryFade) {
         return new Distances(vanillaDistance, vanillaDistance);
      }

      float inset = Math.clamp(config.lodBoundaryInset, 8, 32);
      float requestedWidth = Math.clamp(config.lodBoundaryFadeLength, 8, 64);
      float fadeEnd = Math.max(MIN_VANILLA_RADIUS, vanillaDistance - inset);
      float availableWidth = Math.max(0.0F, fadeEnd - MIN_VANILLA_RADIUS);
      float fadeWidth = Math.min(requestedWidth, availableWidth);
      if (fadeWidth < 1.0F) {
         return new Distances(vanillaDistance, vanillaDistance);
      }

      return new Distances(fadeEnd - fadeWidth, fadeEnd);
   }
}
