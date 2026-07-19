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
        return SableClientRenderDistance.extendVanillaRenderDistanceChunks(renderDistanceChunks);
    }
}
