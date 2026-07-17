package me.cortex.voxy.client.mixin.sodium;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuSampler;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlTexelBuffer;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer extends ShaderChunkRenderer {
   public MixinDefaultChunkRenderer(RenderDevice device, ChunkVertexType vertexType) {
      super(device, vertexType);
   }

   @Inject(method = "render", at = @At("HEAD"), cancellable = true)
   private void voxy$cancelThingie(
      ChunkRenderMatrices matrices,
      CommandList commandList,
      ChunkRenderListIterable renderLists,
      TerrainRenderPass renderPass,
      CameraTransform camera,
      FogParameters parameters,
      boolean indexedRenderingEnabled,
      GpuSampler terrainSampler,
      GpuBufferSlice uniformData,
      GlTexelBuffer sectionTimeInfo,
      CallbackInfo ci
   ) {
      if (VoxyClient.disableSodiumChunkRender()) {
         super.begin(renderPass, parameters, terrainSampler, uniformData, sectionTimeInfo);
         this.doRender(matrices, renderPass, camera, parameters);
         super.end(renderPass);
         ci.cancel();
      }
   }

   @Inject(
      method = "render",
      at = @At(
         value = "INVOKE",
         target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V",
         shift = Shift.BEFORE
      )
   )
   private void voxy$injectRender(
      ChunkRenderMatrices matrices,
      CommandList commandList,
      ChunkRenderListIterable renderLists,
      TerrainRenderPass renderPass,
      CameraTransform camera,
      FogParameters parameters,
      boolean indexedRenderingEnabled,
      GpuSampler terrainSampler,
      GpuBufferSlice uniformData,
      GlTexelBuffer sectionTimeInfo,
      CallbackInfo ci
   ) {
      this.doRender(matrices, renderPass, camera, parameters);
   }

   @Unique
   private void doRender(ChunkRenderMatrices matrices, TerrainRenderPass renderPass, CameraTransform camera, FogParameters fogParameters) {
      if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
         VoxyRenderSystem renderer = IVoxyRenderSystemHolder.getNullable();
         if (renderer != null) {
            Viewport<?> viewport = null;
            RenderTarget target = renderPass.getTarget();
            if (IrisUtil.irisShaderPackEnabled()) {
               viewport = renderer.getViewport();
            } else {
               viewport = renderer.setupViewport(
                  matrices.projection(), matrices.modelView(), fogParameters, target.width, target.height, camera.x, camera.y, camera.z
               );
            }

            renderer.renderOpaque(viewport, ((GlTexture)target.getDepthTexture()).glId(), ((GlTexture)target.getColorTexture()).glId());
         }
      }
   }
}
