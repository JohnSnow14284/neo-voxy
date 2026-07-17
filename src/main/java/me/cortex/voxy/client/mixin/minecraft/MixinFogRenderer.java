package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.CloudRenderContext;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FogRenderer.class, remap = true)
public class MixinFogRenderer {
    @Inject(
            method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
            at = @At("TAIL")
    )
    private static void voxy$overrideFog(Camera camera, FogMode fogMode, float viewDistance,
                                         boolean thickFog, float tickDelta, CallbackInfo ci) {
        var renderer = IGetVoxyRenderSystem.getNullable();
        if (renderer == null || IrisUtil.irisShaderPackEnabled() || CloudRenderContext.isActive()) {
            return;
        }

        float originalEnd = RenderSystem.getShaderFogEnd();
        boolean shortRangeFog = originalEnd < 10.0f;

        if (fogMode == FogMode.FOG_SKY && !shortRangeFog) {
            RenderSystem.setShaderFogStart(0.0f);
            RenderSystem.setShaderFogEnd(VoxyConfig.CONFIG.skyFogDistance * 16.0f);
            return;
        }

        if (fogMode != FogMode.FOG_TERRAIN) {
            return;
        }

        boolean normalTerrainFog = camera.getFluidInCamera() == FogType.NONE && !shortRangeFog && !thickFog;
        // Use the configured LOD fog distance instead of stretching fog across
        // the entire Voxy render distance (which made it effectively invisible).
        float capturedEnd = normalTerrainFog
                ? Math.max(RenderSystem.getShaderFogStart() + 1.01f,
                        VoxyConfig.CONFIG.skyFogDistance * 16.0f)
                : originalEnd;
        // Disabling Voxy's optional distance fog must not disable fog that represents the
        // camera's physical medium or a vanilla near-range effect. The LOD colour target is
        // composited after vanilla terrain, so it has to reproduce those mandatory fog values.
        renderer.setCapturedFog(RenderSystem.getShaderFogStart(), capturedEnd,
                RenderSystem.getShaderFogColor(), !normalTerrainFog);

        if (normalTerrainFog) {
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        }
    }
}
