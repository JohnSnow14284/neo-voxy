package me.cortex.voxy.client.core.rendering.bounding;

import java.util.Arrays;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.UnsafeUtil;

public final class StreamedBoundStore implements IBoundStore {
   private static final int INIT_MAX_CHUNK_COUNT = 4096;
   private GlBuffer chunkPosBuffer = new GlBuffer(32768L);
   private int count;
   private boolean didChange = false;
   private int[] visibleSections = new int[8192];

   @Override
   public void preRender(Viewport<?> viewport) {
      if (this.count != 0 && this.didChange) {
         if (this.count * 4L > this.chunkPosBuffer.size()) {
            this.chunkPosBuffer.free();
            this.chunkPosBuffer = new GlBuffer((long)Math.ceil(this.count * 1.25) * 4L);
         }

         long addr = UploadStream.INSTANCE.upload(this.chunkPosBuffer, 0L, this.count * 4);
         UnsafeUtil.memcpy(this.visibleSections, this.count, addr);
         UploadStream.INSTANCE.commit();
         this.didChange = false;
      }
   }

   public void reset() {
      this.count = 0;
      this.didChange = true;
   }

   public void put(long pos) {
      pos = IBoundStore.transformBeforeStore(pos);
      this.visibleSections[this.count++] = (int)(pos & 4294967295L);
      this.visibleSections[this.count++] = (int)(pos >>> 32 & 4294967295L);
      if (this.count >= this.visibleSections.length - 2) {
         this.visibleSections = Arrays.copyOf(this.visibleSections, (int)(this.visibleSections.length * 1.25));
      }
   }

   @Override
   public void free() {
      this.chunkPosBuffer.free();
   }

   @Override
   public GlBuffer getBuffer() {
      return this.chunkPosBuffer;
   }

   @Override
   public int getCount() {
      return this.count >> 1;
   }
}
