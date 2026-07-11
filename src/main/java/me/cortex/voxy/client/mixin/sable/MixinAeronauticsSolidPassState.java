package me.cortex.voxy.client.mixin.sable;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.lwjgl.opengl.GL11C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Repairs the parent-world SOLID terrain state after Sable has replayed Aeronautics' custom chunk
 * layers for transformed sub-levels.
 *
 * <p>Aeronautics can temporarily disable colour/depth writes or switch the culled face while
 * drawing its special layers. With Sable in the same pipeline, setup and cleanup can observe
 * different mutable shader states, leaving the next Sodium SOLID pass effectively invisible. The
 * result is characteristic: full cubes and the dirt base of grass blocks disappear, while cutout
 * overlays, plants, torches and translucent water remain.</p>
 *
 * <p>This guard runs once per normal-world SOLID pass, allocates nothing, and does not touch Sable
 * sub-level geometry or Iris shadow rendering.</p>
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false, priority = 1200)
public abstract class MixinAeronauticsSolidPassState {
    @Unique
    private static boolean voxy$loggedStateGuard;

    @Inject(method = "render", at = @At("HEAD"))
    private void voxy$restoreParentSolidState(
            ChunkRenderMatrices matrices,
            CommandList commandList,
            ChunkRenderListIterable renderLists,
            TerrainRenderPass renderPass,
            CameraTransform camera,
            boolean indexedRenderingEnabled,
            CallbackInfo ci
    ) {
        if (renderPass != DefaultTerrainRenderPasses.SOLID || IrisUtil.irisShadowActive()) {
            return;
        }

        // Force the small canonical state subset required by Sodium's opaque chunk pass. Using the
        // RenderSystem entry points keeps Minecraft's state cache coherent; glCullFace is set
        // explicitly because Aeronautics and Sable also change that selector through raw OpenGL.
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        GL11C.glCullFace(GL11C.GL_BACK);
        GL11C.glFrontFace(GL11C.GL_CCW);
        RenderSystem.polygonOffset(0.0F, 0.0F);
        RenderSystem.disablePolygonOffset();

        if (!voxy$loggedStateGuard) {
            voxy$loggedStateGuard = true;
            Logger.info("Enabled Sable/Aeronautics parent SOLID terrain state guard");
        }
    }
}
