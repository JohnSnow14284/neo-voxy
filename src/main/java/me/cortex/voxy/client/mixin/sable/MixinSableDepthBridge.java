package me.cortex.voxy.client.mixin.sable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.voxy.client.compat.sable.SableDepthBridge;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Optional bridge around Sable's transformed chunk renderer. The target is named as a string so
 * Voxy does not gain a hard compile/runtime dependency on Sable.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher", remap = false, priority = 1100)
public abstract class MixinSableDepthBridge {
    @WrapMethod(method = "renderSectionLayer")
    private void voxy$shareLodDepthWithSubLevels(
            Iterable<?> subLevels,
            RenderType renderType,
            ShaderInstance shader,
            double cameraX,
            double cameraY,
            double cameraZ,
            Matrix4f modelView,
            Matrix4f projection,
            float partialTicks,
            Operation<Void> original
    ) {
        boolean bridged = SableDepthBridge.enter(modelView, projection, subLevels);
        try {
            original.call(subLevels, renderType, shader, cameraX, cameraY, cameraZ, modelView, projection, partialTicks);
        } finally {
            if (bridged) {
                SableDepthBridge.exit();
            }
        }
    }
}
