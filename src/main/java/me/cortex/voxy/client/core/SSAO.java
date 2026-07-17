package me.cortex.voxy.client.core;

import java.util.List;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import org.joml.Matrix4f;
import org.lwjgl.opengl.ARBComputeShader;
import org.lwjgl.opengl.ARBShaderImageLoadStore;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;

public class SSAO {
   private final Shader ssaoCompute;
   private final boolean isBetterSSAO;
   private final int spp;
   private final int depthSampler;

   public static SSAO createSSAO(RenderProperties properties, SSAO.SSAOMode mode) {
      if (mode == SSAO.SSAOMode.BASIC) {
         return new SSAO(properties);
      } else if (mode == SSAO.SSAOMode.BETTER) {
         return new SSAO(properties, true, 12);
      } else if (mode == SSAO.SSAOMode.BEST) {
         return new SSAO(properties, true, 24);
      } else if (mode == SSAO.SSAOMode.AUTO) {
         if (Capabilities.INSTANCE.canQueryGpuMemory) {
            if (Capabilities.INSTANCE.totalDedicatedMemory < 2500000000L) {
               return createSSAO(properties, SSAO.SSAOMode.BASIC);
            } else {
               return Capabilities.INSTANCE.totalDedicatedMemory < 7000000000L
                  ? createSSAO(properties, SSAO.SSAOMode.BETTER)
                  : createSSAO(properties, SSAO.SSAOMode.BEST);
            }
         } else {
            return Capabilities.INSTANCE.isAmd ? createSSAO(properties, SSAO.SSAOMode.BETTER) : createSSAO(properties, SSAO.SSAOMode.BASIC);
         }
      } else {
         throw new IllegalArgumentException();
      }
   }

   public SSAO(RenderProperties properties) {
      this(properties, false, 0);
   }

   public SSAO(RenderProperties properties, boolean betterSSAO, int samples) {
      Shader.Builder<Shader> builder = Shader.make().apply(properties::apply).add(ShaderType.COMPUTE, "voxy:post/ssao.comp");
      this.spp = samples;
      boolean useConstArray = true;
      this.isBetterSSAO = betterSSAO;
      if (betterSSAO) {
         builder.define("BETTER_SSAO").defineIf("SSAO_STEPS", samples != 0, samples).defineIf("USE_GENERATED_SAMPLE_POINTS", useConstArray);
         if (useConstArray) {
            String array = "";

            for (int i = 0; i < samples; i++) {
               array = array + "vec2(";
               float a = (i + 0.5F) * (1.0F / samples);
               float base = (float)(i * 0.618033988768953 + 0.5);
               float r = (float)Math.sqrt(base % 1.0F);
               float theta = a * (float) (Math.PI * 2);
               array = array + (float)(r * Math.cos(theta));
               array = array + "f, ";
               array = array + (float)(r * Math.sin(theta));
               array = array + "f)";
               if (i != samples - 1) {
                  array = array + ", ";
               }
            }

            builder.replace("%%CONST_ARRAY%%", array);
         }
      }

      this.ssaoCompute = builder.compile();
      this.depthSampler = GL45C.glCreateSamplers();
      if (this.isBetterSSAO) {
         GL33C.glSamplerParameteri(this.depthSampler, 10241, 9984);
         GL33C.glSamplerParameteri(this.depthSampler, 10240, 9728);
      } else {
         GL33C.glSamplerParameteri(this.depthSampler, 10241, 9729);
         GL33C.glSamplerParameteri(this.depthSampler, 10240, 9729);
      }

      GL33C.glSamplerParameteri(this.depthSampler, 34892, 0);
      GL33C.glSamplerParameteri(this.depthSampler, 10242, 33071);
      GL33C.glSamplerParameteri(this.depthSampler, 10243, 33071);
   }

   public void computeSSAO(Viewport<?> viewport, GlTexture colourOut, GlTexture colourIn, GlTexture baseDepthTex, int sourceDepthTexture) {
      this.ssaoCompute.bind();
      MemoryStack stack = MemoryStack.stackPush();

      try {
         long ptr = stack.nmalloc(64);
         Matrix4f scratch = new Matrix4f();
         if (this.isBetterSSAO) {
            viewport.projection.getToAddress(ptr);
            GL20C.nglUniformMatrix4fv(4, 1, false, ptr);
            viewport.projection.invert(scratch).getToAddress(ptr);
            GL20C.nglUniformMatrix4fv(5, 1, false, ptr);
            viewport.modelView.getToAddress(ptr);
            GL20C.nglUniformMatrix4fv(6, 1, false, ptr);
            viewport.vanillaProjection.invert(scratch).getToAddress(ptr);
            GL20C.nglUniformMatrix4fv(7, 1, false, ptr);
         } else {
            viewport.MVP.getToAddress(ptr);
            GL20C.nglUniformMatrix4fv(3, 1, false, ptr);
            viewport.MVP.invert(scratch).getToAddress(ptr);
            GL20C.nglUniformMatrix4fv(4, 1, false, ptr);
         }
      } catch (Throwable var11) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var10) {
               var11.addSuppressed(var10);
            }
         }

         throw var11;
      }

      if (stack != null) {
         stack.close();
      }

      ARBShaderImageLoadStore.glBindImageTexture(0, colourOut.id, 0, false, 0, 35002, 32856);
      GL45C.glBindTextureUnit(1, colourIn.id);
      GL33C.glBindSampler(1, 0);
      GL45C.glBindTextureUnit(2, baseDepthTex.id);
      GL33C.glBindSampler(2, this.depthSampler);
      if (this.isBetterSSAO) {
         GL45C.glBindTextureUnit(3, sourceDepthTexture);
         GL33C.glBindSampler(3, this.depthSampler);
      }

      ARBComputeShader.glDispatchCompute((viewport.width + 7) / 8, (viewport.height + 7) / 8, 1);
      GL45C.glBindTextureUnit(1, 0);
      GL33C.glBindSampler(1, 0);
      GL45C.glBindTextureUnit(2, 0);
      GL33C.glBindSampler(2, 0);
      GL45C.glBindTextureUnit(3, 0);
      GL33C.glBindSampler(3, 0);
   }

   public void free() {
      GL33C.glDeleteSamplers(this.depthSampler);
      this.ssaoCompute.free();
   }

   public void addDebugInfo(List<String> debugLines) {
      debugLines.add("SSAO: " + (this.isBetterSSAO ? "new (" + this.spp + " spp)" : "basic"));
   }

   public static enum SSAOMode {
      AUTO,
      BASIC,
      BETTER,
      BEST;
   }
}
