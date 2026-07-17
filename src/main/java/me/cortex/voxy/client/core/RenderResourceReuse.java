package me.cortex.voxy.client.core;

import java.util.ArrayList;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.ThreadUtils;
import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.opengl.GL11C;

public class RenderResourceReuse {
   private static final ArrayList<GlTexture> MODEL_TEXTURE_CACHE = new ArrayList<>();
   private static final ArrayList<GlBuffer> GEOMETRY_BUFFER_CACHE = new ArrayList<>();

   public static void clearResources() {
      MODEL_TEXTURE_CACHE.forEach(TrackedObject::free);
      GEOMETRY_BUFFER_CACHE.forEach(TrackedObject::free);
      MODEL_TEXTURE_CACHE.clear();
      GEOMETRY_BUFFER_CACHE.clear();
   }

   public static GlTexture getOrCreateModelStoreTextureAtlas() {
      GlTexture atlas = null;
      if (!MODEL_TEXTURE_CACHE.isEmpty()) {
         atlas = MODEL_TEXTURE_CACHE.removeFirst().zero();
      } else {
         atlas = new GlTexture().store(32856, Integer.numberOfTrailingZeros(16), 12288, 8192).name("ModelTextures");
      }

      return atlas;
   }

   public static void giveBackModelStoreTextureAtlas(GlTexture texture) {
      MODEL_TEXTURE_CACHE.add(texture);
   }

   static GlBuffer getOrCreateGeometryBuffer() {
      GlBuffer buffer = null;
      if (!GEOMETRY_BUFFER_CACHE.isEmpty()) {
         buffer = GEOMETRY_BUFFER_CACHE.removeFirst();
      } else {
         long capacity = getGeometryBufferSize();
         long driverMemory = -1L;
         if (Capabilities.INSTANCE.canQueryGpuMemory) {
            driverMemory = Capabilities.INSTANCE.getFreeDedicatedGpuMemory();
         }

         GL11C.glGetError();
         if (Capabilities.INSTANCE.isNvidia && ThreadUtils.isWindows && Capabilities.INSTANCE.sparseBuffer) {
            Logger.info("Running on nvidia, using workaround sparse buffer allocation");
         } else {
            buffer = new GlBuffer(capacity, false);
         }

         int error = GL11C.glGetError();
         if (error != 0 || buffer == null) {
            if (buffer != null && error != 1285 || !Capabilities.INSTANCE.sparseBuffer) {
               throw new IllegalStateException("Unable to allocate geometry buffer, got gl error " + error + ". Failed to allocate buffer of size " + capacity);
            }

            if (buffer != null) {
               Logger.error("Failed to allocate geometry buffer, attempting workaround with sparse buffers");
               buffer.free();
            }

            buffer = new GlBuffer(capacity, 1024);
            error = GL11C.glGetError();
            if (error != 0) {
               buffer.free();
               throw new IllegalStateException(
                  "Unable to allocate geometry buffer using workaround, got gl error " + error + ". Failed to allocate buffer of size " + capacity
               );
            }
         }

         String extra = "";
         if (driverMemory != -1L) {
            extra = ", driver stated " + driverMemory / 1048576L + "Mb of free memory";
         }

         Logger.info("Allocated new geometry buffer: " + buffer.size() + ", isSparse: " + buffer.isSparse() + extra);
      }

      return buffer;
   }

   public static void giveBackGeometryBuffer(GlBuffer geometryBuffer) {
      GEOMETRY_BUFFER_CACHE.add(geometryBuffer);
   }

   private static long getGeometryBufferSize() {
      long geometryCapacity = Math.min(1L << 64 - Long.numberOfLeadingZeros(Capabilities.INSTANCE.ssboMaxSize - 1L) << 1, 4294967296L) - 1024L;
      if (Capabilities.INSTANCE.isIntel) {
         geometryCapacity = Math.max(geometryCapacity, 1073741824L);
      }

      if (Capabilities.INSTANCE.isNvidia && ThreadUtils.isLinux) {
         geometryCapacity = Math.min(geometryCapacity, 2097152000L);
      }

      geometryCapacity = Math.max(536870912L, geometryCapacity);
      if (Capabilities.INSTANCE.canQueryGpuMemory) {
         long limit = Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - 1610612736L;
         limit = Math.max(536870912L, limit);
         geometryCapacity = Math.min(geometryCapacity, limit);
      }

      String override = System.getProperty("voxy.geometryBufferSizeOverrideMB", "");
      if (!override.isEmpty()) {
         geometryCapacity = Long.parseLong(override) * 1024L * 1024L;
      }

      return geometryCapacity;
   }
}
