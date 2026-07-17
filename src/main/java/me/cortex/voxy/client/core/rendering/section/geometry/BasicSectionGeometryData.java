package me.cortex.voxy.client.core.rendering.section.geometry;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.ThreadUtils;
import org.lwjgl.opengl.ARBSparseBuffer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;

public class BasicSectionGeometryData implements IGeometryData {
   public static final int SECTION_METADATA_SIZE = 32;
   private final GlBuffer sectionMetadataBuffer;
   private final GlBuffer geometryBuffer;
   public final boolean isExternalGeometryBuffer;
   private final int maxSectionCount;
   private int currentSectionCount;
   private long sparseCommitment = 0L;

   public BasicSectionGeometryData(int maxSectionCount, GlBuffer geometryBuffer) {
      this.maxSectionCount = maxSectionCount;
      this.sectionMetadataBuffer = new GlBuffer(maxSectionCount * 32L);
      if (geometryBuffer.size() % 8L != 0L) {
         throw new IllegalStateException();
      } else {
         this.geometryBuffer = geometryBuffer;
         this.isExternalGeometryBuffer = true;
      }
   }

   public BasicSectionGeometryData(int maxSectionCount, long geometryCapacity) {
      this.isExternalGeometryBuffer = false;
      this.maxSectionCount = maxSectionCount;
      this.sectionMetadataBuffer = new GlBuffer(maxSectionCount * 32L);
      if (geometryCapacity % 8L != 0L) {
         throw new IllegalStateException();
      } else {
         long start = System.currentTimeMillis();
         String msg = "Creating and zeroing " + geometryCapacity / 1048576L + "MB geometry buffer";
         if (Capabilities.INSTANCE.canQueryGpuMemory) {
            msg = msg + " driver states " + Capabilities.INSTANCE.getFreeDedicatedGpuMemory() / 1048576L + "MB of free memory";
         }

         Logger.info(msg);
         Logger.info("if your game crashes/exits here without any other log message, try manually decreasing the geometry capacity");
         GL11C.glGetError();
         GlBuffer buffer = null;
         if (Capabilities.INSTANCE.isNvidia && ThreadUtils.isWindows && Capabilities.INSTANCE.sparseBuffer) {
            Logger.info("Running on nvidia, using workaround sparse buffer allocation");
         } else {
            buffer = new GlBuffer(geometryCapacity, false);
         }

         int error = GL11C.glGetError();
         if (error != 0 || buffer == null) {
            if (buffer != null && error != 1285 || !Capabilities.INSTANCE.sparseBuffer) {
               throw new IllegalStateException("Unable to allocate geometry buffer, got gl error " + error);
            }

            if (buffer != null) {
               Logger.error("Failed to allocate geometry buffer, attempting workaround with sparse buffers");
               buffer.free();
            }

            buffer = new GlBuffer(geometryCapacity, 1024);
            error = GL11C.glGetError();
            if (error != 0) {
               buffer.free();
               throw new IllegalStateException("Unable to allocate geometry buffer using workaround, got gl error " + error);
            }
         }

         this.geometryBuffer = buffer;
         long delta = System.currentTimeMillis() - start;
         Logger.info("Successfully allocated the geometry buffer in " + delta + "ms");
      }
   }

   public void ensureAccessable(int maxElementAccess) {
      long size = Integer.toUnsignedLong(maxElementAccess) * 8L + 65535L & -65536L;
      if (this.geometryBuffer.isSparse() && this.sparseCommitment < size) {
         GL15C.glBindBuffer(34962, this.geometryBuffer.id);
         size += 67108864L;
         ARBSparseBuffer.glBufferPageCommitmentARB(34962, this.sparseCommitment, size - this.sparseCommitment, true);
         GL15C.glBindBuffer(34962, 0);
         this.sparseCommitment = size;
      }
   }

   public GlBuffer getGeometryBuffer() {
      return this.geometryBuffer;
   }

   public GlBuffer getMetadataBuffer() {
      return this.sectionMetadataBuffer;
   }

   @Override
   public int getSectionCount() {
      return this.currentSectionCount;
   }

   public void setSectionCount(int count) {
      this.currentSectionCount = count;
   }

   public int getMaxSectionCount() {
      return this.maxSectionCount;
   }

   public long getGeometryCapacityBytes() {
      return this.geometryBuffer.size();
   }

   @Override
   public void free() {
      this.sectionMetadataBuffer.free();
      long gpuMemory = 0L;
      if (Capabilities.INSTANCE.canQueryGpuMemory) {
         GL11C.glFinish();
         gpuMemory = Capabilities.INSTANCE.getFreeDedicatedGpuMemory();
      }

      if (this.geometryBuffer.isSparse()) {
         GL15C.glBindBuffer(34962, this.geometryBuffer.id);
         ARBSparseBuffer.glBufferPageCommitmentARB(34962, 0L, this.sparseCommitment, false);
         GL15C.glBindBuffer(34962, 0);
      }

      GL11C.glFinish();
      if (!this.isExternalGeometryBuffer) {
         this.geometryBuffer.free();
         GL11C.glFinish();
         if (Capabilities.INSTANCE.canQueryGpuMemory) {
            long releaseSize = (long)(this.geometryBuffer.size() * 0.75);
            if (this.geometryBuffer.isSparse()) {
               releaseSize = (long)(this.sparseCommitment * 0.75);
            }

            if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - gpuMemory <= releaseSize) {
               Logger.info("Attempting to wait for gpu memory to release");
               long start = System.currentTimeMillis();
               long TIMEOUT = 400L;

               while (System.currentTimeMillis() - start < TIMEOUT) {
                  GL11C.glFinish();
                  if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - gpuMemory > releaseSize) {
                     break;
                  }
               }

               if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - gpuMemory <= releaseSize) {
                  Logger.warn("Failed to wait for gpu memory to be freed, this could indicate an issue with the driver");
               }
            }
         }
      }
   }

   @Override
   public long getMaxCapacity() {
      return this.geometryBuffer.size();
   }
}
