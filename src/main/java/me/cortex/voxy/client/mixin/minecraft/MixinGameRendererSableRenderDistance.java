package me.cortex.voxy.client.mixin.minecraft;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.cortex.voxy.client.compat.sable.SableClientRenderDistance;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class MixinGameRendererSableRenderDistance {
    @ModifyExpressionValue(
            method = "renderLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I")
    )
    private int voxy$extendSableFrameRenderDistance(int renderDistanceChunks) {
        //Leave the value alone while vision is restricted. This rewrites every
        //getEffectiveRenderDistance call inside renderLevel, and one of them becomes
        //FogRenderer.setupFog's farPlaneDistance, which every fog producer scales its band off. A fog
        //implementation that derives blindness as a fraction of that distance - BCLib's
        //CustomFogRenderer uses farPlaneDistance * 0.03 - then stretches a 7-block band to 250 and the
        //world stops going dark. Sable's sub-levels have nothing to show through blindness either.
        if (me.cortex.voxy.client.core.VoxyRenderSystem.visionEffectPresent()) {
            return renderDistanceChunks;
        }
        return SableClientRenderDistance.extendVanillaRenderDistanceChunks(renderDistanceChunks);
    }
}
