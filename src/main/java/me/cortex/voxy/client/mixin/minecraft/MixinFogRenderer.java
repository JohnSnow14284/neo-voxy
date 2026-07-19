package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.systems.RenderSystem;

@Mixin(value = FogRenderer.class, remap = true)
public class MixinFogRenderer {
    @Inject(
        method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
        at = @At("TAIL")
    )
    private static void voxy$overrideFog(
        Camera camera,
        FogMode fogMode,
        float viewDistance,
        boolean thickFog,
        float tickDelta,
        CallbackInfo ci
    ) {
        var vrs = IGetVoxyRenderSystem.getNullable();
        if (vrs == null) return;

        boolean terrainFog = fogMode == FogMode.FOG_TERRAIN;
        boolean submerged = camera.getFluidInCamera() != FogType.NONE;
        boolean visionRestricted = camera.getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS));

        // Underwater visibility starts very close and expands while the player's water vision
        // settles. Capture it even while fogEnd is below the generic special-fog guard; otherwise
        // Voxy keeps a stale/no fog state precisely during the visible moving boundary.
        if (terrainFog && (submerged || visionRestricted)) {
            vrs.setCapturedFog(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(),
                    RenderSystem.getShaderFogColor(), RenderSystem.getShaderFogShape().getIndex(), true);
            return;
        }

        if (RenderSystem.getShaderFogEnd() < 10.0f) return;

        // Adjust sky fog so it always looks smooth and doesn't change with render distance
        if (fogMode == FogMode.FOG_SKY) {
            RenderSystem.setShaderFogStart(0);
            RenderSystem.setShaderFogEnd(VoxyConfig.CONFIG.skyFogDistance);
        }

        if (terrainFog) {
            // Do NOT override unique fog, it's always displayed close and meant for restricting vision
            boolean noFogType = camera.getFluidInCamera() == FogType.NONE;

            // Capture original fog values BEFORE we modify them,
            // so Voxy's own fog pass can use the correct values
            float capturedFogEnd = noFogType ?
                VoxyConfig.CONFIG.sectionRenderDistance * 32 * 16 : RenderSystem.getShaderFogEnd();

            vrs.setCapturedFog(RenderSystem.getShaderFogStart(), capturedFogEnd,
                    RenderSystem.getShaderFogColor(), RenderSystem.getShaderFogShape().getIndex(), false);

            // Always hide vanilla terrain fog - either replaced by voxy or disabled completely
            // unless it's special fog, in that case it must be rendered to restrict vision in regular chunks
            if (noFogType) {
                RenderSystem.setShaderFogStart(999999999);
                RenderSystem.setShaderFogEnd(999999999);
            }
        }
    }
}
