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

        //Media that restrict vision - blindness, darkness, and being inside a fluid - own the fog and
        //must keep it: vanilla terrain goes dark from it, and the LOD has to follow or the world beyond
        //the vanilla render distance stays lit while everything nearer is black. Capture the live values
        //and leave vanilla's fog alone. It has to run ahead of the short-fog guard below, since a
        //restricting fog is a short one and would be skipped by it.
        if (fogMode == FogMode.FOG_TERRAIN
                && (camera.getFluidInCamera() != FogType.NONE
                    || (camera.getEntity() instanceof LivingEntity living
                        && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS))))) {
            return;
        }

        if (RenderSystem.getShaderFogEnd() < 10.0f) return;

        // Adjust sky fog so it always looks smooth and doesn't change with render distance
        if (fogMode == FogMode.FOG_SKY) {
            RenderSystem.setShaderFogStart(0);
            RenderSystem.setShaderFogEnd(VoxyConfig.CONFIG.skyFogDistance);
        }

        if (fogMode == FogMode.FOG_TERRAIN) {
            // Do NOT override unique fog, it's always displayed close and meant for restricting vision
            boolean noFogType = camera.getFluidInCamera() == FogType.NONE;

            // Always hide vanilla terrain fog - either replaced by voxy or disabled completely
            // unless it's special fog, in that case it must be rendered to restrict vision in regular chunks.
            //
            //The medium is re-tested here rather than inferred from having taken the branch above:
            //setupFog is cancellable at HEAD and mods do cancel it, so an earlier return in this method
            //is not proof the later code is unreachable. Sodium's chunk shaders read this fog state
            //directly (ChunkShaderFogComponent$Smooth#setup), so clobbering it while vision is
            //restricted leaves vanilla terrain drawn to the render distance edge with no fog at all.
            if (noFogType && !me.cortex.voxy.client.core.VoxyRenderSystem.restrictingMediumPresent()) {
                RenderSystem.setShaderFogStart(999999999);
                RenderSystem.setShaderFogEnd(999999999);
            }
        }
    }
}
