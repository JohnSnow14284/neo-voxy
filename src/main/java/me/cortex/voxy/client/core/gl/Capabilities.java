package me.cortex.voxy.client.core.gl;

import java.util.Locale;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.common.Logger;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

public class Capabilities {
   public static final Capabilities INSTANCE = new Capabilities();
   public final boolean repFragTest;
   public final boolean meshShaders;
   public final boolean INT64_t;
   public final long ssboMaxSize;
   public final int ssboBindingAlignment;
   public final boolean isMesa;
   public final boolean canQueryGpuMemory;
   public final long totalDedicatedMemory;
   public final long totalDynamicMemory;
   public final boolean compute;
   public final boolean indirectParameters;
   public final boolean isIntel;
   public final boolean subgroup;
   public final boolean sparseBuffer;
   public final boolean isNvidia;
   public final boolean isAmd;
   public final boolean nvBarryCoords;
   public final boolean hasBrokenDepthSampler;

   public Capabilities() {
      GLCapabilities cap = GL.getCapabilities();
      this.sparseBuffer = cap.GL_ARB_sparse_buffer;
      this.compute = cap.glDispatchComputeIndirect != 0L;
      this.indirectParameters = cap.glMultiDrawElementsIndirectCountARB != 0L;
      this.repFragTest = cap.GL_NV_representative_fragment_test;
      this.meshShaders = cap.GL_NV_mesh_shader;
      this.canQueryGpuMemory = cap.GL_NVX_gpu_memory_info;
      this.INT64_t = testShaderCompilesOk(
         ShaderType.COMPUTE,
         "#version 430\n#extension GL_ARB_gpu_shader_int64 : require\nlayout(local_size_x=32) in;\nvoid main() {\n    uint64_t a = 1234;\n}\n"
      );
      if (cap.GL_KHR_shader_subgroup) {
         this.subgroup = testShaderCompilesOk(
            ShaderType.COMPUTE,
            "#version 430\n#extension GL_KHR_shader_subgroup_basic : require\n#extension GL_KHR_shader_subgroup_arithmetic : require\nlayout(local_size_x=32) in;\nvoid main() {\n    uint a = subgroupExclusiveAdd(gl_LocalInvocationIndex);\n}\n"
         );
      } else {
         this.subgroup = false;
      }

      this.ssboMaxSize = GL32.glGetInteger64(37086);
      this.ssboBindingAlignment = GL45C.glGetInteger(37087);
      this.isMesa = GL45C.glGetString(7938).toLowerCase(Locale.ROOT).contains("mesa");
      String vendor = GL45C.glGetString(7936).toLowerCase(Locale.ROOT);
      this.isIntel = vendor.contains("intel");
      this.isNvidia = vendor.contains("nvidia");
      this.isAmd = vendor.contains("amd") || vendor.contains("radeon");
      if (this.canQueryGpuMemory) {
         this.totalDedicatedMemory = GL32.glGetInteger64(36935) * 1024L;
         this.totalDynamicMemory = GL32.glGetInteger64(36936) * 1024L - this.totalDedicatedMemory;
      } else {
         this.totalDedicatedMemory = -1L;
         this.totalDynamicMemory = -1L;
      }

      this.nvBarryCoords = cap.GL_NV_fragment_shader_barycentric;
      if (this.compute && this.isAmd) {
         this.hasBrokenDepthSampler = testDepthSampler();
         if (this.hasBrokenDepthSampler) {
            throw new IllegalStateException("it bork, amd is bork");
         }
      } else {
         this.hasBrokenDepthSampler = false;
      }
   }

   public static void init() {
   }

   private static boolean testDepthSampler() {
      String src = "#version 460 core\nlayout(local_size_x=16,local_size_y=16) in;\n\nlayout(binding = 0) uniform sampler2D depthSampler;\nlayout(binding = 1) buffer OutData {\n    float[] outData;\n};\n\nlayout(location = 2) uniform int dynamicSampleThing;\nlayout(location = 3) uniform float sampleData;\n\nvoid main() {\n    if (abs(texelFetch(depthSampler, ivec2(gl_GlobalInvocationID.xy), dynamicSampleThing).r-sampleData)>0.000001f) {\n        outData[0] = 1.0;\n    }\n}\n";
      int program = GL20C.glCreateProgram();
      int shader = GL20C.glCreateShader(ShaderType.COMPUTE.gl);
      GL20C.glShaderSource(shader, src);
      GL20C.glCompileShader(shader);
      if (GL20C.glGetShaderi(shader, 35713) != 1) {
         GL20C.glDeleteShader(shader);
         throw new IllegalStateException("Shader compile fail");
      } else {
         GL20C.glAttachShader(program, shader);
         GL20C.glLinkProgram(program);
         GL45C.glDeleteShader(shader);
         shader = GL45C.glCreateBuffers();
         GL45C.glNamedBufferStorage(shader, 4096L, 257);
         int tex = GL45C.glCreateTextures(3553);
         GL45C.glTextureStorage2D(tex, 2, 35056, 256, 256);
         GL45C.glTextureParameteri(tex, 10241, 9728);
         GL45C.glTextureParameteri(tex, 10240, 9728);
         int fb = GL45C.glCreateFramebuffers();
         boolean isCorrect = true;

         for (int lvl = 0; lvl <= 1; lvl++) {
            GL45C.glNamedFramebufferTexture(fb, 33306, tex, lvl);

            for (int i = 0; i <= 10; i++) {
               float value = (float)(i / 10.0);
               GL45C.nglClearNamedBufferSubData(shader, 33326, 0L, 4096L, 6403, 5126, 0L);
               GL45.glClearNamedFramebufferfi(fb, 34041, 0, value, 1);
               GL45C.glUseProgram(program);
               GL45C.glUniform1i(2, lvl);
               GL45C.glUniform1f(3, value);
               GL45C.glBindTextureUnit(0, tex);
               GL30.glBindBufferBase(37074, 1, shader);
               GL45C.glDispatchCompute(256 >> lvl + 4, 256 >> lvl + 4, 1);
               GL45C.glFinish();
               long ptr = GL45C.nglMapNamedBuffer(shader, 35000);
               float gottenValue = MemoryUtil.memGetFloat(ptr);
               GL45C.glUnmapNamedBuffer(shader);
               GL45C.glUseProgram(0);
               GL45C.glBindTextureUnit(0, 0);
               GL45C.glBindBuffer(37074, 0);
               boolean localCorrect = gottenValue == 0.0F;
               if (!localCorrect) {
                  Logger.error("Depth read test failed at value: " + value);
               }

               isCorrect &= localCorrect;
            }
         }

         GL45C.glDeleteFramebuffers(fb);
         GL45C.glDeleteTextures(tex);
         GL15.glDeleteBuffers(shader);
         GL45C.glDeleteProgram(program);
         return !isCorrect;
      }
   }

   private static boolean testShaderCompilesOk(ShaderType type, String src) {
      int shader = GL20C.glCreateShader(type.gl);
      GL20C.glShaderSource(shader, src);
      GL20C.glCompileShader(shader);
      int result = GL20C.glGetShaderi(shader, 35713);
      GL20C.glDeleteShader(shader);
      return result == 1;
   }

   public long getFreeDedicatedGpuMemory() {
      if (!this.canQueryGpuMemory) {
         throw new IllegalStateException("Cannot query gpu memory, missing extension");
      } else {
         return GL32.glGetInteger64(36937) * 1024L;
      }
   }
}
