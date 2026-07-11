package me.cortex.voxy.client.mixin.sable;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves Voxy's terrain draw to the end of Sodium's cutout layer when Sable is present.
 *
 * <p>Sable and Create: Aeronautics both add work around SodiumWorldRenderer.drawChunkLayer. A
 * low-priority tail hook lets those renderers finish first, so Voxy consumes the final terrain
 * depth/state instead of observing an intermediate state. This avoids framebuffer copies and has
 * no per-frame allocations.</p>
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false, priority = 850)
public abstract class MixinSablePostTerrainRenderer {
    @Unique
    private static boolean voxy$loggedLateHook;

    @Inject(
            method = "drawChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;DDD)V",
            at = @At("TAIL")
    )
    private void voxy$renderAfterSableTerrain(
            RenderType renderLayer,
            ChunkRenderMatrices matrices,
            double cameraX,
            double cameraY,
            double cameraZ,
            CallbackInfo ci
    ) {
        if (renderLayer != RenderType.cutout() || IrisUtil.irisShadowActive()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer == null) {
            return;
        }

        var renderer = ((IGetVoxyRenderSystem) minecraft.levelRenderer).voxy$getRenderSystem();
        if (renderer == null) {
            return;
        }

        Viewport<?> viewport;
        if (IrisUtil.irisShaderPackEnabled()) {
            viewport = renderer.getViewport();
        } else {
            viewport = renderer.setupViewport(
                    matrices.projection(),
                    matrices.modelView(),
                    cameraX,
                    cameraY,
                    cameraZ
            );
        }

        if (viewport == null) {
            return;
        }

        renderer.renderOpaque(viewport);

        if (!voxy$loggedLateHook) {
            voxy$loggedLateHook = true;
            Logger.info("Using late Sodium terrain hook for Sable/Aeronautics compatibility");
        }
    }
}
