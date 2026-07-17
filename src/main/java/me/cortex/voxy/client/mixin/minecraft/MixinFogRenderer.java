package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = FogRenderer.class, priority = 900)
public class MixinFogRenderer {
   @Inject(method = "setupFog", at = @At("RETURN"))
   private void voxy$modifyFog(
      Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker, float darkenWorldAmount, ClientLevel level, CallbackInfoReturnable<FogData> cir
   ) {
      if (VoxyConfig.CONFIG.isRenderingEnabled()) {
         VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
         if (vrs != null) {
            FogData data = (FogData)cir.getReturnValue();
            boolean fogIsDamnClose = data.environmentalEnd < 10.0F;
            if (!VoxyConfig.CONFIG.useEnvironmentalFog && !fogIsDamnClose) {
               data.environmentalStart = 1.0E8F;
               data.environmentalEnd = 1.0E8F;
            }

            data.renderDistanceStart = 1.0E9F;
            data.renderDistanceEnd = 1.0E9F;
         }
      }
   }
}
