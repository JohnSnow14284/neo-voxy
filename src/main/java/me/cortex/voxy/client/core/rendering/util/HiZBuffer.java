package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL45C;

public class HiZBuffer {
   private final Shader hiz;
   private final GlFramebuffer fb = new GlFramebuffer().name("HiZ");
   private final int sampler = GL33.glGenSamplers();
   private final int type;
   private GlTexture texture;
   private int levels;
   private int width;
   private int height;
   private final RenderProperties properties;

   public HiZBuffer(RenderProperties properties) {
      this(properties, 35056);
   }

   public HiZBuffer(RenderProperties properties, int type) {
      ARBDirectStateAccess.glNamedFramebufferDrawBuffer(this.fb.id, 0);
      this.type = type;
      this.hiz = Shader.make()
         .apply(properties::apply)
         .add(ShaderType.VERTEX, "voxy:hiz/blit.vsh")
         .add(ShaderType.FRAGMENT, "voxy:hiz/blit.fsh")
         .compile()
         .name("HiZ Builder");
      this.properties = properties;
   }

   private void alloc(int width, int height) {
      this.levels = (int)Math.ceil(Math.log(Math.max(width, height)) / Math.log(2.0));
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
      this.fb.bind(36096, this.texture, 0).verify();
   }

   public void buildMipChain(int srcDepthTex, int width, int height) {
      if (this.width != Integer.highestOneBit(width) || this.height != Integer.highestOneBit(height)) {
         if (this.texture != null) {
            this.texture.free();
            this.texture = null;
         }

         this.alloc(Integer.highestOneBit(width), Integer.highestOneBit(height));
      }

      GL30C.glBindVertexArray(GlVertexArray.STATIC_VAO);
      int boundFB = GL11.glGetInteger(36006);
      this.hiz.bind();
      GL30C.glBindFramebuffer(36160, this.fb.id);
      GL30C.glDepthFunc(519);
      GL30C.glDepthMask(true);
      GL30C.glEnable(2929);
      ARBDirectStateAccess.glBindTextureUnit(0, srcDepthTex);
      GL33.glBindSampler(0, this.sampler);
      GL30C.glUniform1i(0, 0);
      int cw = this.width;
      int ch = this.height;

      for (int i = 0; i < this.levels; i++) {
         this.fb.bind(36096, this.texture, i);
         GL30C.glViewport(0, 0, cw, ch);
         cw = Math.max(cw / 2, 1);
         ch = Math.max(ch / 2, 1);
         GL30C.glDrawArrays(6, 0, 4);
         GL45C.glTextureBarrier();
         GL42C.glMemoryBarrier(1032);
         ARBDirectStateAccess.glTextureParameteri(this.texture.id, 33084, i);
         ARBDirectStateAccess.glTextureParameteri(this.texture.id, 33085, i);
         if (i == 0) {
            ARBDirectStateAccess.glBindTextureUnit(0, this.texture.id);
         }
      }

      ARBDirectStateAccess.glTextureParameteri(this.texture.id, 33084, 0);
      ARBDirectStateAccess.glTextureParameteri(this.texture.id, 33085, 1000);
      GL30C.glDepthFunc(this.properties.closerEqualDepthCompare());
      GL30C.glDisable(2929);
      GL30C.glBindFramebuffer(36160, boundFB);
      GL30C.glViewport(0, 0, width, height);
      GL30C.glBindVertexArray(0);
   }

   public void free() {
      this.fb.free();
      if (this.texture != null) {
         this.texture.free();
         this.texture = null;
      }

      GL33C.glDeleteSamplers(this.sampler);
      this.hiz.free();
   }

   public int getHizTextureId() {
      return this.texture.id;
   }

   public int getPackedLevels() {
      return this.width << 16 | this.height;
   }
}
