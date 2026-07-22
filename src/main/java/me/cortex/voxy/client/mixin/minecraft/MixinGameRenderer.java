package me.cortex.voxy.client.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = {GameRenderer.class}, priority = 1100)
public class MixinGameRenderer {
    @WrapMethod(method = "getDepthFar()F")
    public float getDepthFar(Operation<Float> original) {
        //Hand the vanilla far plane back while vision is restricted. This value is not only the
        //projection far plane: it is what GameRenderer passes to FogRenderer.setupFog as
        //farPlaneDistance, and every fog producer in the stack scales its band off it - vanilla's own
        //formulas, and BCLib's CustomFogRenderer, which cancels setupFog at HEAD and computes
        //fogEnd = farPlaneDistance / density. Inflating it to cover the LOD therefore inflates the
        //blindness/darkness band by the same factor, which is why the world stops going dark. The LOD
        //is not drawn under those effects anyway (VoxyRenderSystem.visionRestricted), so nothing needs
        //the extended plane just then.
        if (VoxyConfig.CONFIG.isRenderingEnabled()
                && !me.cortex.voxy.client.core.VoxyRenderSystem.visionEffectPresent()) {
            return Math.max(original.call(), VoxyConfig.CONFIG.sectionRenderDistance * 32F * 4F);
        }
        return original.call();
    }
}
