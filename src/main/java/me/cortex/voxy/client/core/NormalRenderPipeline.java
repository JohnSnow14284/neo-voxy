package me.cortex.voxy.client.core;

import java.util.List;
import java.util.function.BooleanSupplier;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.util.GPUTiming;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;

public class NormalRenderPipeline extends AbstractRenderPipeline {
   private GlTexture colourTex;
   private GlTexture colourSSAOTex;
   private final GlFramebuffer fbSSAO = new GlFramebuffer();
   private final boolean useEnvFog = VoxyConfig.CONFIG.useEnvironmentalFog;
   private final FullscreenBlit finalBlit;
   private final SSAO ssao;

   protected NormalRenderPipeline(
      RenderProperties properties,
      AsyncNodeManager nodeManager,
      NodeCleaner nodeCleaner,
      HierarchicalOcclusionTraverser traversal,
      BooleanSupplier frexSupplier
   ) {
      super(properties, nodeManager, nodeCleaner, traversal, frexSupplier, false);
      this.finalBlit = new FullscreenBlit(
         properties, "voxy:post/blit_texture_depth_cutout.frag", a -> a.defineIf("USE_ENV_FOG", this.useEnvFog).define("EMIT_COLOUR")
      );
      this.ssao = SSAO.createSSAO(properties, VoxyConfig.CONFIG.getSSAOMode());
   }

   @Override
   protected int setup(Viewport<?> viewport, int sourceDepthTex, int srcWidth, int srcHeight) {
      if (this.colourTex == null || this.colourTex.getHeight() != viewport.height || this.colourTex.getWidth() != viewport.width) {
         if (this.colourTex != null) {
            this.colourTex.free();
            this.colourSSAOTex.free();
         }

         this.fb.resize(viewport.width, viewport.height);
         this.colourTex = new GlTexture().store(32856, 1, viewport.width, viewport.height);
         this.colourSSAOTex = new GlTexture().store(32856, 1, viewport.width, viewport.height);
         this.fb.framebuffer.bind(36064, this.colourTex).verify();
         this.fbSSAO.bind(this.fb.getDepthAttachmentType(), this.fb.getDepthTex()).bind(36064, this.colourSSAOTex).verify();
         GL45C.glTextureParameterf(this.colourTex.id, 10241, 9728.0F);
         GL45C.glTextureParameterf(this.colourTex.id, 10240, 9728.0F);
         GL45C.glTextureParameterf(this.colourSSAOTex.id, 10241, 9728.0F);
         GL45C.glTextureParameterf(this.colourSSAOTex.id, 10240, 9728.0F);
         GL45C.glTextureParameterf(this.fb.getDepthTex().id, 37098, 6402.0F);
      }

      this.initDepthStencil(sourceDepthTex, this.fb.framebuffer.id, srcWidth, srcHeight, viewport.width, viewport.height);
      return this.fb.getDepthTex().id;
   }

   @Override
   protected void postOpaquePreTranslucent(Viewport<?> viewport, int sourceDepthTexture) {
      GPUTiming.INSTANCE.marker("ao");
      this.ssao.computeSSAO(viewport, this.colourSSAOTex, this.colourTex, this.fb.getDepthTex(), sourceDepthTexture);
      GL30C.glBindFramebuffer(36160, this.fbSSAO.id);
   }

   @Override
   protected void finish(Viewport<?> viewport, int sourceDepthTexture, int outputFramebuffer, int srcWidth, int srcHeight) {
      this.finalBlit.bind();
      boolean fogCoversAllRendering = viewport.fogParameters.environmentalEnd() < VoxyRenderSystem.getRenderDistance();
      if (this.useEnvFog) {
         float start = viewport.fogParameters.environmentalStart();
         float end = viewport.fogParameters.environmentalEnd();
         if (Math.abs(end - start) > 1.0F) {
            float invEndFogDelta = 1.0F / (end - start);
            float endDistance = Math.max(VoxyRenderSystem.getRenderDistance(), 320.0F);
            endDistance *= (float)Math.sqrt(3.0);
            float startDelta = -start * invEndFogDelta;
            GL30C.glUniform4f(4, invEndFogDelta, startDelta, Math.clamp(endDistance * invEndFogDelta + startDelta, 0.0F, 1.0F), 0.0F);
            GL30C.glUniform4f(5, viewport.fogParameters.red(), viewport.fogParameters.green(), viewport.fogParameters.blue(), viewport.fogParameters.alpha());
         } else {
            GL30C.glUniform4f(4, 0.0F, 0.0F, 0.0F, 0.0F);
            GL30C.glUniform4f(5, 0.0F, 0.0F, 0.0F, 0.0F);
         }
      }

      GL45C.glBindTextureUnit(3, this.colourSSAOTex.id);
      if (!fogCoversAllRendering) {
         GL30C.glEnable(3042);
         GL30C.glBlendFuncSeparate(770, 771, 1, 771);
         AbstractRenderPipeline.transformBlitDepth(
            this.finalBlit, this.fb.getDepthTex().id, outputFramebuffer, viewport, new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView)
         );
         GL30C.glDisable(3042);
      } else {
         GL30C.glDisable(2960);
         GL30C.glDisable(2929);
      }
   }

   @Override
   public void setupAndBindOpaque(Viewport<?> viewport) {
      this.fb.bind();
   }

   @Override
   public void setupAndBindTranslucent(Viewport<?> viewport) {
      GL30C.glBindFramebuffer(36160, this.fbSSAO.id);
   }

   @Override
   public void free() {
      this.finalBlit.delete();
      this.ssao.free();
      this.fbSSAO.free();
      if (this.colourTex != null) {
         this.colourTex.free();
         this.colourSSAOTex.free();
      }

      super.free0();
   }

   @Override
   public void addDebug(List<String> debug) {
      super.addDebug(debug);
      this.ssao.addDebugInfo(debug);
   }
}
