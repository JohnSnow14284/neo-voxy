package me.cortex.voxy.client.core.rendering.bounding;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL42;
import org.lwjgl.system.MemoryUtil;

public class BoundRenderer {
   private final GlBuffer uniformBuffer = new GlBuffer(128L);
   private final Shader rasterShader;
   private final RenderProperties properties;
   private final AbstractRenderPipeline pipeline;

   public BoundRenderer(AbstractRenderPipeline pipeline) {
      this.properties = pipeline.properties;
      String vert = ShaderLoader.parse("voxy:chunkoutline/outline.vsh");
      String taa = pipeline.taaFunction("getTAA");
      if (taa != null) {
         this.pipeline = pipeline;
         vert = vert + "\n\n\n" + taa;
      } else {
         this.pipeline = null;
      }

      this.rasterShader = Shader.makeAuto()
         .addSource(ShaderType.VERTEX, vert)
         .defineIf("TAA", taa != null)
         .add(ShaderType.FRAGMENT, "voxy:chunkoutline/outline.fsh")
         .apply(this.properties::apply)
         .compile()
         .ubo(0, this.uniformBuffer);
   }

   public void render(Viewport<?> viewport, IBoundStore store) {
      store.preRender(viewport);
      int count = store.getCount();
      if (count == 0) {
         viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
      } else {
         ((AutoBindingShader)this.rasterShader).ssbo(1, store.getBuffer());
         float renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
         this.renderInner(viewport, renderDistance, count);
         store.postRender(viewport);
      }
   }

   private void renderInner(Viewport<?> viewport, float renderDistanceBlocks, int chunkCount) {
      viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
      if (chunkCount != 0) {
         long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0L, 128L);
         long var12 = ptr + 64L;
         int bx = (int)Math.floor(viewport.cameraX);
         int by = (int)Math.floor(viewport.cameraY);
         int bz = (int)Math.floor(viewport.cameraZ);
         new Vector3i(bx, by, bz).getToAddress(var12);
         long var13 = var12 + 16L;
         Vector3f negInnerBlock = new Vector3f((float)(viewport.cameraX - bx), (float)(viewport.cameraY - by), (float)(viewport.cameraZ - bz));
         negInnerBlock.getToAddress(var13);
         long var14 = var13 + 12L;
         viewport.MVP.translate(negInnerBlock.negate(), new Matrix4f()).getToAddress(ptr);
         MemoryUtil.memPutFloat(var14, renderDistanceBlocks);
         ptr = var14 + 4L;
         UploadStream.INSTANCE.commit();
         GL30C.glFrontFace(2304);
         GL30C.glEnable(2884);
         GL30C.glEnable(2929);
         GL30C.glDepthFunc(this.properties.furtherDepthCompare());
         GL30.glBindVertexArray(GlVertexArray.STATIC_VAO);
         viewport.depthBoundingBuffer.bind();
         this.rasterShader.bind();
         GL15.glBindBuffer(34963, SharedIndexBuffer.INSTANCE_BB_BYTE.id());
         if (this.pipeline != null) {
            this.pipeline.bindUniforms();
         }

         if (chunkCount >= 32) {
            GL31.glDrawElementsInstanced(4, 1152, 5121, 0L, chunkCount / 32);
         }

         if (chunkCount % 32 != 0) {
            GL42.glDrawElementsInstancedBaseInstance(4, 36 * (chunkCount % 32), 5121, 0L, 1, chunkCount / 32 * 32);
         }

         GL30C.glFrontFace(2305);
         GL30C.glDepthFunc(this.properties.closerEqualDepthCompare());
         GL30C.glEnable(2884);
         GL30C.glEnable(2929);
      }
   }

   public void free() {
      this.rasterShader.free();
      this.uniformBuffer.free();
   }
}
