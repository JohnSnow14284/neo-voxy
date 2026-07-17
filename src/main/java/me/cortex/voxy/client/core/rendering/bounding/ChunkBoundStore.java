package me.cortex.voxy.client.core.rendering.bounding;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.system.MemoryUtil;

public class ChunkBoundStore implements IBoundStore {
   private static final int INIT_MAX_CHUNK_COUNT = 4096;
   private GlBuffer chunkPosBuffer = new GlBuffer(32768L);
   private final Long2IntOpenHashMap chunk2idx = new Long2IntOpenHashMap(4096);
   private long[] idx2chunk = new long[4096];
   private final LongOpenHashSet addQueue = new LongOpenHashSet();
   private final LongOpenHashSet remQueue = new LongOpenHashSet();

   public ChunkBoundStore() {
      this.chunk2idx.defaultReturnValue(-1);
   }

   public void addSection(long pos) {
      if (!this.remQueue.remove(pos)) {
         this.addQueue.add(pos);
      }
   }

   public void removeSection(long pos) {
      if (!this.addQueue.remove(pos)) {
         this.remQueue.add(pos);
      }
   }

   @Override
   public void preRender(Viewport<?> viewport) {
      if (!this.remQueue.isEmpty()) {
         boolean wasEmpty = this.chunk2idx.isEmpty();
         this.remQueue.forEach(this::_remPos);
         this.remQueue.clear();
      }
   }

   @Override
   public void postRender(Viewport<?> viewport) {
      if (!this.addQueue.isEmpty()) {
         this.addQueue.forEach(this::_addPos);
         this.addQueue.clear();
         UploadStream.INSTANCE.commit();
      }
   }

   private void _remPos(long pos) {
      int idx = this.chunk2idx.remove(pos);
      if (idx == -1) {
         Logger.warn("Chunk not in map: " + pos);
      } else if (idx != this.chunk2idx.size()) {
         if (this.idx2chunk[idx] != pos) {
            throw new IllegalStateException();
         } else {
            long ePos = this.idx2chunk[this.chunk2idx.size()];
            if (this.chunk2idx.put(ePos, idx) == -1) {
               throw new IllegalStateException();
            } else {
               this.idx2chunk[idx] = ePos;
               this.put(idx, ePos);
            }
         }
      }
   }

   private void _addPos(long pos) {
      if (this.chunk2idx.containsKey(pos)) {
         Logger.warn("Chunk already in map: " + pos);
      } else {
         this.ensureSize1();
         int idx = this.chunk2idx.size();
         this.chunk2idx.put(pos, idx);
         this.idx2chunk[idx] = pos;
         this.put(idx, pos);
      }
   }

   private void ensureSize1() {
      if (this.chunk2idx.size() >= this.idx2chunk.length) {
         UploadStream.INSTANCE.commit();
         int size = (int)(this.idx2chunk.length * 1.5);
         Logger.info("Resizing chunk position buffer to: " + size);
         GlBuffer old = this.chunkPosBuffer;
         this.chunkPosBuffer = new GlBuffer(size * 8L);
         ARBDirectStateAccess.glCopyNamedBufferSubData(old.id, this.chunkPosBuffer.id, 0L, 0L, old.size());
         old.free();
         long[] old2 = this.idx2chunk;
         this.idx2chunk = new long[size];
         System.arraycopy(old2, 0, this.idx2chunk, 0, old2.length);
      }
   }

   private void put(int idx, long pos) {
      long ptr2 = UploadStream.INSTANCE.upload(this.chunkPosBuffer, 8L * idx, 8L);
      putPos(ptr2, pos);
   }

   private static void putPos(long ptr, long pos) {
      pos = IBoundStore.transformBeforeStore(pos);
      MemoryUtil.memPutInt(ptr, (int)(pos & 4294967295L));
      ptr += 4L;
      MemoryUtil.memPutInt(ptr, (int)(pos >>> 32 & 4294967295L));
   }

   public void reset() {
      this.chunk2idx.clear();
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
      return this.chunk2idx.size();
   }
}
