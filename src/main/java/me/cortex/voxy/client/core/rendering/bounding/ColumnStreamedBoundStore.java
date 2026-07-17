package me.cortex.voxy.client.core.rendering.bounding;

import it.unimi.dsi.fastutil.longs.LongIterator;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.MemoryBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.lwjgl.system.MemoryUtil;

public class ColumnStreamedBoundStore implements IBoundStore {
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

      int by = (int)Math.floor(viewport.cameraY);
      float fy = (float)(viewport.cameraY - (int)viewport.cameraY);
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
      ChunkTracker tracker = ChunkTrackerHolder.get(Minecraft.getInstance().level);
      if (tracker != null) {
         LongIterator iter = tracker.getReadyChunks().longIterator();

         while (iter.hasNext()) {
            long column = iter.nextLong();

            for (int cy = mincy + 1; cy < maxcy; cy++) {
               if (count++ < capacity && writePtr != 0L) {
                  putPos(writePtr, SectionPos.asLong(ChunkPos.getX(column), cy, ChunkPos.getZ(column)));
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
