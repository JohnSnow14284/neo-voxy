package me.cortex.voxy.client.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = {GameRenderer.class}, priority = 1100)
public class MixinGameRenderer {
    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true, require = 0)
    private void voxy$skipLevelRenderWithoutCameraEntity(CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        // During disconnect Minecraft may retain the ClientLevel for one frame
        // after clearing the player/camera entity. Camera.setup cannot accept a
        // null entity, so do not begin another world render during that window.
        if (minecraft.level == null || minecraft.player == null || minecraft.getCameraEntity() == null) {
            ci.cancel();
        }
    }

    @WrapMethod(method = "getDepthFar()F")
    public float getDepthFar(Operation<Float> original) {
        if (VoxyConfig.CONFIG.isRenderingEnabled()) {
            return Math.max(original.call(), VoxyConfig.CONFIG.sectionRenderDistance * 32F * 4F);
        }
        return original.call();
    }
}
