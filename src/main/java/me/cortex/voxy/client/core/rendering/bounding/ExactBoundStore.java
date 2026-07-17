package me.cortex.voxy.client.core.rendering.bounding;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.MemoryBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryUtil;

public class ExactBoundStore implements IBoundStore {
   private static final int INIT_MAX_CHUNK_COUNT = 4096;
   private GlBuffer chunkPosBuffer = new GlBuffer(32768L);
   private int count;

   @Override
   public void preRender(Viewport<?> viewport) {
      float renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
      long addr = UploadStream.INSTANCE.uploadTo(this.chunkPosBuffer);
      this.count = findEmitBoundingChunks(viewport, renderDistance, (int)(this.chunkPosBuffer.size() / 8L), addr);
      UploadStream.INSTANCE.commit();
      if (this.count < 0) {
         this.chunkPosBuffer.free();
         MemoryBuffer chunkPoss = new MemoryBuffer(-this.count * 8L);
         this.count = findEmitBoundingChunks(viewport, renderDistance, -this.count, chunkPoss.address);
         if (this.count < 0) {
            chunkPoss.free();
            throw new IllegalStateException("Not sure how this is possible, should have exact capacity. badness: " + this.count);
         }

         this.chunkPosBuffer = new GlBuffer(chunkPoss);
         chunkPoss.free();
      }
   }

   private static void putPos(long ptr, long pos) {
      pos = IBoundStore.transformBeforeStore(pos);
      MemoryUtil.memPutInt(ptr, (int)(pos & 4294967295L));
      ptr += 4L;
      MemoryUtil.memPutInt(ptr, (int)(pos >>> 32 & 4294967295L));
   }

   @Override
   public void free() {
      this.chunkPosBuffer.free();
   }

   private static int findEmitBoundingChunks(Viewport<?> viewport, float searchDistance, int capacity, long writePtr) {
      if (capacity == -1) {
         writePtr = 0L;
         capacity = Integer.MAX_VALUE;
      }

      float d2 = searchDistance * searchDistance;
      int bx = (int)Math.floor(viewport.cameraX);
      int by = (int)Math.floor(viewport.cameraY);
      int bz = (int)Math.floor(viewport.cameraZ);
      float fy = (float)(viewport.cameraY - (int)viewport.cameraY);
      float fx = (float)(viewport.cameraX - (int)viewport.cameraX);
      float fz = (float)(viewport.cameraZ - (int)viewport.cameraZ);
      int mincy = by >> 4;
      int maxcy = by >> 4;

      while (testYPos(by, fy, mincy, searchDistance)) {
         mincy--;
      }

      mincy++;

      while (testYPos(by, fy, maxcy, searchDistance)) {
         maxcy++;
      }

      maxcy--;
      int count = 0;
      long bpos = Integer.toUnsignedLong(bx) | Integer.toUnsignedLong(bz) << 32;
      int minsx = ((int)Math.floor(viewport.cameraX - searchDistance) >> 4) - 2;
      int maxsx = ((int)Math.ceil(viewport.cameraX + searchDistance) >> 4) + 2;
      int minsz = ((int)Math.floor(viewport.cameraZ - searchDistance) >> 4) - 2;
      int maxsz = ((int)Math.ceil(viewport.cameraZ + searchDistance) >> 4) + 2;

      for (int cx = minsx; cx < maxsx; cx++) {
         for (int cz = minsz; cz < maxsz; cz++) {
            if (testXZPos(bpos, fx, fz, cx, cz, d2)) {
               if (!testXZPos(bpos, fx, fz, cx + 1, cz + 1, d2)
                  || !testXZPos(bpos, fx, fz, cx - 1, cz + 1, d2)
                  || !testXZPos(bpos, fx, fz, cx + 1, cz - 1, d2)
                  || !testXZPos(bpos, fx, fz, cx - 1, cz - 1, d2)) {
                  for (int cy = mincy + 1; cy < maxcy; cy++) {
                     if (count++ < capacity && writePtr != 0L) {
                        putPos(writePtr, SectionPos.asLong(cx, cy, cz));
                        writePtr += 8L;
                     }
                  }
               }

               if (maxcy != mincy) {
                  if (count++ < capacity && writePtr != 0L) {
                     putPos(writePtr, SectionPos.asLong(cx, maxcy, cz));
                     writePtr += 8L;
                  }

                  if (count++ < capacity && writePtr != 0L) {
                     putPos(writePtr, SectionPos.asLong(cx, mincy, cz));
                     writePtr += 8L;
                  }
               } else if (count++ < capacity && writePtr != 0L) {
                  putPos(writePtr, SectionPos.asLong(cx, mincy, cz));
                  writePtr += 8L;
               }
            }
         }
      }

      return count > capacity ? -count : count;
   }

   private static boolean testYPos(int by, float fy, int cy, float d) {
      int ry = cy * 16 - by;
      float dy = nearestToZero(ry - 1, ry + 17) - fy;
      return Math.abs(dy) < d;
   }

   private static boolean testXZPos(long bpos, float fx, float fz, int cx, int cy, float d2) {
      int rx = cx * 16 - (int)bpos;
      int rz = cy * 16 - (int)(bpos >> 32);
      float dx = nearestToZero(rx - 1, rx + 17) - fx;
      float dz = nearestToZero(rz - 1, rz + 17) - fz;
      return dx * dx + dz * dz < d2;
   }

   private static int nearestToZero(int min, int max) {
      int clamped = 0;
      if (min > 0) {
         clamped = min;
      }

      if (max < 0) {
         clamped = max;
      }

      return clamped;
   }

   @Override
   public GlBuffer getBuffer() {
      return this.chunkPosBuffer;
   }

   @Override
   public int getCount() {
      return this.count;
   }
}
