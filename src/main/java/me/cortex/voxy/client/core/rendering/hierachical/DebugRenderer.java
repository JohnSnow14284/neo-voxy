package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

public class DebugRenderer {
   private final Shader debugShader = Shader.make()
      .add(ShaderType.VERTEX, "voxy:lod/hierarchical/debug/node_outline.vert")
      .add(ShaderType.FRAGMENT, "voxy:lod/hierarchical/debug/frag.frag")
      .compile();
   private final Shader setupShader = Shader.make().add(ShaderType.COMPUTE, "voxy:lod/hierarchical/debug/setup.comp").compile();
   private final GlBuffer uniformBuffer = new GlBuffer(1024L).zero();
   private final GlBuffer drawBuffer = new GlBuffer(1024L).zero();

   private void uploadUniform(Viewport<?> viewport) {
      long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0L, 1024L);
      int sx = Mth.floor(viewport.cameraX) >> 5;
      int sy = Mth.floor(viewport.cameraY) >> 5;
      int sz = Mth.floor(viewport.cameraZ) >> 5;
      new Matrix4f(viewport.projection).mul(viewport.modelView).getToAddress(ptr);
      ptr += 64L;
      MemoryUtil.memPutInt(ptr, sx);
      ptr += 4L;
      MemoryUtil.memPutInt(ptr, sy);
      ptr += 4L;
      MemoryUtil.memPutInt(ptr, sz);
      ptr += 4L;
      MemoryUtil.memPutInt(ptr, viewport.width);
      ptr += 4L;
      Vector3f innerTranslation = new Vector3f(
         (float)(viewport.cameraX - (sx << 5)), (float)(viewport.cameraY - (sy << 5)), (float)(viewport.cameraZ - (sz << 5))
      );
      innerTranslation.getToAddress(ptr);
      ptr += 12L;
      MemoryUtil.memPutInt(ptr, viewport.height);
      ptr += 4L;
   }

   public void render(Viewport<?> viewport, GlBuffer nodeData, GlBuffer nodeList) {
      this.uploadUniform(viewport);
      UploadStream.INSTANCE.commit();
      this.setupShader.bind();
      GL43.glBindBufferBase(37074, 0, this.drawBuffer.id);
      GL43.glBindBufferBase(37074, 1, nodeList.id);
      GL43.glDispatchCompute(1, 1, 1);
      GL42.glMemoryBarrier(8256);
      GL43.glEnable(2929);
      this.debugShader.bind();
      GL43.glBindVertexArray(GlVertexArray.STATIC_VAO);
      GL15C.glBindBuffer(36671, this.drawBuffer.id);
      GL15.glBindBuffer(34963, SharedIndexBuffer.INSTANCE_BYTE.id());
      GL43.glBindBufferBase(35345, 0, this.uniformBuffer.id);
      GL43.glBindBufferBase(37074, 1, nodeData.id);
      GL43.glBindBufferBase(37074, 2, nodeList.id);
      GL40.glDrawElementsIndirect(4, 5121, 0L);
   }

   public void free() {
      this.drawBuffer.free();
      this.uniformBuffer.free();
      this.debugShader.free();
      this.setupShader.free();
   }
}
