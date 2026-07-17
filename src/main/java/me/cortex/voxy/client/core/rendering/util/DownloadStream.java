package me.cortex.voxy.client.core.rendering.util;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.function.Consumer;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlFence;
import me.cortex.voxy.client.core.gl.GlPersistentMappedBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL45;

public class DownloadStream {
   private final AllocationArena allocationArena = new AllocationArena();
   private final GlPersistentMappedBuffer downloadBuffer;
   private final Deque<DownloadStream.DownloadFrame> frames = new ArrayDeque<>();
   private final LongArrayList thisFrameAllocations = new LongArrayList();
   private final Deque<DownloadStream.DownloadData> downloadList = new ArrayDeque<>();
   private final ArrayList<DownloadStream.DownloadData> thisFrameDownloadList = new ArrayList<>();
   private long caddr = -1L;
   private long offset = 0L;
   public static final DownloadStream INSTANCE = new DownloadStream(33554432L);

   public DownloadStream(long size) {
      this.downloadBuffer = new GlPersistentMappedBuffer(size, 1);
      this.allocationArena.setLimit(size);
   }

   public void download(GlBuffer buffer, DownloadStream.DownloadResultConsumer resultConsumer) {
      this.download(buffer, 0L, buffer.size(), resultConsumer);
   }

   public void download(GlBuffer buffer, Consumer<MemoryBuffer> resultConsumer) {
      this.download(buffer, 0L, buffer.size(), resultConsumer);
   }

   public void download(GlBuffer buffer, long downloadOffset, long size, Consumer<MemoryBuffer> consumer) {
      this.download(buffer, downloadOffset, size, (ptr, size2) -> consumer.accept(MemoryBuffer.createUntrackedUnfreeableRawFrom(ptr, size)));
   }

   public void download(GlBuffer buffer, long downloadOffset, long size, DownloadStream.DownloadResultConsumer resultConsumer) {
      if (size > 2147483647L) {
         throw new IllegalArgumentException();
      } else if (size <= 0L) {
         throw new IllegalArgumentException();
      } else if (downloadOffset + size > buffer.size()) {
         throw new IllegalArgumentException();
      } else {
         long addr;
         if (this.caddr != -1L && this.allocationArena.expand(this.caddr, (int)size)) {
            addr = this.caddr + this.offset;
            this.offset += size;
         } else {
            this.caddr = this.allocationArena.alloc((int)size);
            if (this.caddr == -1L) {
               Logger.warn("Download stream full, preemptively committing, this could cause bad things to happen");
               this.commit();

               for (int attempts = 10; --attempts != 0 && this.caddr == -1L; this.caddr = this.allocationArena.alloc((int)size)) {
                  GL11.glFinish();
                  this.tick();
               }

               if (this.caddr == -1L) {
                  throw new IllegalStateException("Could not allocate memory segment big enough for upload even after force flush");
               }
            }

            this.thisFrameAllocations.add(this.caddr);
            this.offset = size;
            addr = this.caddr;
         }

         if (this.caddr + size > this.downloadBuffer.size()) {
            throw new IllegalStateException();
         } else {
            this.downloadList.add(new DownloadStream.DownloadData(buffer, addr, downloadOffset, size, resultConsumer));
            this.commit();
         }
      }
   }

   public void commit() {
      if (!this.downloadList.isEmpty()) {
         GL42.glMemoryBarrier(512);

         for (DownloadStream.DownloadData entry : this.downloadList) {
            GL45.glCopyNamedBufferSubData(entry.target.id, this.downloadBuffer.id, entry.targetOffset, entry.downloadStreamOffset, entry.size);
         }

         GL42.glMemoryBarrier(16896);
         this.thisFrameDownloadList.addAll(this.downloadList);
         this.downloadList.clear();
         this.caddr = -1L;
         this.offset = 0L;
      }
   }

   public void tick() {
      this.commit();
      if (!this.thisFrameAllocations.isEmpty()) {
         this.frames
            .add(new DownloadStream.DownloadFrame(new GlFence(), new LongArrayList(this.thisFrameAllocations), new ArrayList<>(this.thisFrameDownloadList)));
         this.thisFrameAllocations.clear();
         this.thisFrameDownloadList.clear();
      }

      while (!this.frames.isEmpty() && this.frames.peek().fence.signaled()) {
         DownloadStream.DownloadFrame frame = this.frames.pop();

         for (DownloadStream.DownloadData data : frame.data) {
            data.resultConsumer.consume(this.downloadBuffer.addr() + data.downloadStreamOffset, data.size);
         }

         frame.allocations.forEach(this.allocationArena::free);
         frame.fence.free();
      }
   }

   public void waitDiscard() {
      GL11.glFinish();
      GlFence fence = new GlFence();
      GL11.glFinish();

      while (!fence.signaled()) {
         Thread.onSpinWait();
      }

      fence.free();

      while (!this.frames.isEmpty()) {
         DownloadStream.DownloadFrame frame = this.frames.pop();

         while (!frame.fence.signaled()) {
            Thread.onSpinWait();
         }

         frame.allocations.forEach(this.allocationArena::free);
         frame.fence.free();
      }
   }

   public void flushWaitClear() {
      GL11.glFinish();
      this.tick();
      GlFence fence = new GlFence();
      GL11.glFinish();

      while (!fence.signaled()) {
         GL11.glFinish();
         Thread.onSpinWait();
      }

      fence.free();
      this.tick();
      if (!this.frames.isEmpty()) {
         throw new IllegalStateException();
      }
   }

   private record DownloadData(GlBuffer target, long downloadStreamOffset, long targetOffset, long size, DownloadStream.DownloadResultConsumer resultConsumer) {
   }

   private record DownloadFrame(GlFence fence, LongArrayList allocations, ArrayList<DownloadStream.DownloadData> data) {
   }

   public interface DownloadResultConsumer {
      void consume(long var1, long var3);
   }
}
