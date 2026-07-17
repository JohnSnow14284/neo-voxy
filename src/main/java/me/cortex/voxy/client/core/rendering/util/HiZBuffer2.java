package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL45C;

public class HiZBuffer2 {
   private final Shader hizMip = Shader.make().add(ShaderType.COMPUTE, "voxy:hiz/hiz.comp").compile();
   private final Shader hizInitial = Shader.make()
      .add(ShaderType.VERTEX, "voxy:hiz/blit.vsh")
      .add(ShaderType.FRAGMENT, "voxy:hiz/blit.fsh")
      .define("OUTPUT_COLOUR")
      .compile();
   private final GlFramebuffer fb = new GlFramebuffer().name("HiZ");
   private final int sampler = GL33.glGenSamplers();
   private final int type;
   private GlTexture texture;
   private int levels;
   private int width;
   private int height;

   public HiZBuffer2() {
      this(33326);
   }

   public HiZBuffer2(int type) {
      ARBDirectStateAccess.glNamedFramebufferDrawBuffer(this.fb.id, 36064);
      this.type = type;
   }

   private void alloc(int width, int height) {
      this.levels = Math.min(7, (int)Math.ceil(Math.log(Math.max(width, height)) / Math.log(2.0)));
      this.texture = new GlTexture().store(this.type, this.levels, width, height).name("HiZ");
      ARBDirectStateAccess.glTextureParameteri(this.texture.id, 10241, 9984);
      ARBDirectStateAccess.glTextureParameteri(this.texture.id, 10240, 9728);
      ARBDirectStateAccess.glTextureParameteri(this.texture.id, 34892, 0);
      ARBDirectStateAccess.glTextureParameteri(this.texture.id, 10242, 33071);
      ARBDirectStateAccess.glTextureParameteri(this.texture.id, 10243, 33071);
      GL33C.glSamplerParameteri(this.sampler, 10241, 9984);
      GL33C.glSamplerParameteri(this.sampler, 10240, 9728);
      GL33C.glSamplerParameteri(this.sampler, 34892, 0);
      GL33C.glSamplerParameteri(this.sampler, 10242, 33071);
      GL33C.glSamplerParameteri(this.sampler, 10243, 33071);
      this.width = width;
      this.height = height;
      this.fb.bind(36064, this.texture, 0).verify();
   }

   public void buildMipChain(int srcDepthTex, int width, int height) {
      if (this.width != Integer.highestOneBit(width) || this.height != Integer.highestOneBit(height)) {
         if (this.texture != null) {
            this.texture.free();
            this.texture = null;
         }

         this.alloc(Integer.highestOneBit(width), Integer.highestOneBit(height));
      }

      int boundFB = GL11.glGetInteger(36006);
      GL42C.glBindVertexArray(GlVertexArray.STATIC_VAO);
      this.hizInitial.bind();
      GL42C.glBindFramebuffer(36160, this.fb.id);
      GL42C.glDisable(2929);
      ARBDirectStateAccess.glBindTextureUnit(0, srcDepthTex);
      GL33.glBindSampler(0, this.sampler);
      GL42C.glUniform1i(0, 0);
      GL42C.glViewport(0, 0, this.width, this.height);
      GL42C.glDrawArrays(6, 0, 4);
      GL45C.glTextureBarrier();
      GL42C.glMemoryBarrier(1032);
      GL42C.glBindFramebuffer(36160, boundFB);
      GL42C.glViewport(0, 0, width, height);
      GL42C.glBindVertexArray(0);
      this.hizMip.bind();
      GL42C.glUniform2f(0, 1.0F / this.width, 1.0F / this.height);
      ARBDirectStateAccess.glBindTextureUnit(0, this.texture.id);
      GL33.glBindSampler(0, this.sampler);

      for (int i = 1; i < 7; i++) {
         GL42C.glBindImageTexture(i, this.texture.id, i, false, 0, 35001, 33326);
      }

      GL43C.glDispatchCompute(this.width / 64, this.height / 64, 1);
      GL33.glBindSampler(0, 0);

      for (int i = 0; i < 7; i++) {
         ARBDirectStateAccess.glBindTextureUnit(i, 0);
      }
   }

   public void free() {
      this.fb.free();
      if (this.texture != null) {
         this.texture.free();
         this.texture = null;
      }

      GL33C.glDeleteSamplers(this.sampler);
      this.hizInitial.free();
      this.hizMip.free();
   }

   public int getHizTextureId() {
      return this.texture.id;
   }

   public int getPackedLevels() {
      return Integer.numberOfTrailingZeros(this.width) << 16 | Integer.numberOfTrailingZeros(this.height);
   }
}
