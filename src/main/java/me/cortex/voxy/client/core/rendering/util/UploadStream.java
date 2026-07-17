package me.cortex.voxy.client.core.rendering.util;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlFence;
import me.cortex.voxy.client.core.gl.GlPersistentMappedBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL45C;

public class UploadStream {
   public static final int BASE_ALLOCATION_ALIGNEMENT = Math.max(Capabilities.INSTANCE.ssboBindingAlignment, 16);
   private final AllocationArena allocationArena = new AllocationArena();
   private final GlPersistentMappedBuffer uploadBuffer;
   private final Deque<UploadStream.UploadFrame> frames = new ArrayDeque<>();
   private final LongArrayList thisFrameAllocations = new LongArrayList();
   private final Deque<UploadStream.UploadData> uploadList = new ArrayDeque<>();
   private static final boolean USE_COHERENT = false;
   private long caddr = -1L;
   private long offset = 0L;
   public static final UploadStream INSTANCE = new UploadStream(67108864L);

   public UploadStream(long size) {
      this.uploadBuffer = new GlPersistentMappedBuffer(size, 562).name("UploadStream");
      this.allocationArena.setLimit(size);
   }

   public void upload(GlBuffer buffer, long destOffset, MemoryBuffer data) {
      data.cpyTo(this.upload(buffer, destOffset, data.size));
   }

   public long uploadTo(GlBuffer buffer) {
      return this.upload(buffer, 0L, buffer.size());
   }

   public long upload(GlBuffer buffer, long destOffset, long size) {
      long addr = this.rawUploadAddress((int)size);
      this.uploadList.add(new UploadStream.UploadData(buffer, addr, destOffset, size));
      return this.uploadBuffer.addr() + addr;
   }

   public long rawUpload(int size) {
      return this.uploadBuffer.addr() + this.rawUploadAddress(size);
   }

   public long rawUploadAddress(int size) {
      if (size < 0) {
         throw new IllegalStateException("Negative size");
      } else {
         size = alignUp(size, BASE_ALLOCATION_ALIGNEMENT);
         if (size > this.uploadBuffer.size()) {
            throw new IllegalArgumentException();
         } else {
            long addr;
            if (this.caddr != -1L && this.allocationArena.expand(this.caddr, size)) {
               addr = this.caddr + this.offset;
               this.offset += size;
            } else {
               if (this.caddr != -1L) {
                  GL45C.glFlushMappedNamedBufferRange(this.uploadBuffer.id, this.caddr, this.offset);
               }

               this.caddr = this.allocationArena.alloc(size);
               if (this.caddr == -1L) {
                  Logger.error("Upload stream full, preemptively committing, this could cause bad things to happen");

                  for (int attempts = 10; --attempts != 0 && this.caddr == -1L; this.caddr = this.allocationArena.alloc(size)) {
                     GL11.glFinish();
                     this.tick(false);
                  }

                  if (this.caddr == -1L) {
                     throw new IllegalStateException("Could not allocate memory segment big enough for upload even after force flush");
                  }
               }

               this.thisFrameAllocations.add(this.caddr);
               this.offset = size;
               addr = this.caddr;
            }

            if (this.caddr + size > this.uploadBuffer.size()) {
               throw new IllegalStateException();
            } else {
               return addr;
            }
         }
      }
   }

   public void commit() {
      if (this.caddr != -1L) {
         GL45C.glFlushMappedNamedBufferRange(this.uploadBuffer.id, this.caddr, this.offset);
      }

      if (!this.uploadList.isEmpty()) {
         GL42.glMemoryBarrier(512);

         for (UploadStream.UploadData entry : this.uploadList) {
            ARBDirectStateAccess.glCopyNamedBufferSubData(this.uploadBuffer.id, entry.target.id, entry.uploadOffset, entry.targetOffset, entry.size);
         }

         this.uploadList.clear();
         GL42.glMemoryBarrier(512);
         this.caddr = -1L;
         this.offset = 0L;
      }
   }

   public void tick() {
      this.tick(true);
   }

   private void tick(boolean commit) {
      if (commit) {
         this.commit();
      }

      if (!this.thisFrameAllocations.isEmpty()) {
         this.frames.add(new UploadStream.UploadFrame(new GlFence(), new LongArrayList(this.thisFrameAllocations)));
         this.thisFrameAllocations.clear();
      }

      while (!this.frames.isEmpty() && this.frames.peek().fence.signaled()) {
         UploadStream.UploadFrame frame = this.frames.pop();
         frame.allocations.forEach(this.allocationArena::free);
         frame.fence.free();
      }
   }

   public long getBaseAddress() {
      return this.uploadBuffer.addr();
   }

   public int getRawBufferId() {
      return this.uploadBuffer.id;
   }

   public static long alignUp(long val, long alignment) {
      return (val + alignment - 1L) / alignment * alignment;
   }

   public static int alignUp(int val, int alignment) {
      return (val + alignment - 1) / alignment * alignment;
   }

   public static int alignUpAlloc(int val) {
      return (val + BASE_ALLOCATION_ALIGNEMENT - 1) / BASE_ALLOCATION_ALIGNEMENT * BASE_ALLOCATION_ALIGNEMENT;
   }

   private record UploadData(GlBuffer target, long uploadOffset, long targetOffset, long size) {
   }

   private record UploadFrame(GlFence fence, LongArrayList allocations) {
   }
}
