package me.cortex.voxy.client.core.rendering.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import me.cortex.voxy.client.core.gl.GlFence;
import me.cortex.voxy.client.core.gl.GlPersistentMappedBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import org.lwjgl.opengl.GL11;

public class RawDownloadStream {
   private final GlPersistentMappedBuffer downloadBuffer;
   private final AllocationArena allocationArena = new AllocationArena();
   private final ArrayList<RawDownloadStream.DownloadFragment> frameFragments = new ArrayList<>();
   private final Deque<RawDownloadStream.DownloadFrame> frames = new ArrayDeque<>();

   public RawDownloadStream(int size) {
      this.downloadBuffer = new GlPersistentMappedBuffer(size, 129).name("RawDownloadStream");
      this.allocationArena.setLimit(size);
   }

   public int download(int size, RawDownloadStream.IDownloadCompletedCallback callback) {
      int allocation = (int)this.allocationArena.alloc(size);
      if (allocation == -1L) {
         Logger.warn("Raw download stream full, preemptively committing, this could cause bad things to happen");
         GL11.glFinish();
         this.tick();
         allocation = (int)this.allocationArena.alloc(size);
         if (allocation == -1L) {
            throw new IllegalStateException("Unable free enough memory for raw download stream");
         }
      }

      this.frameFragments.add(new RawDownloadStream.DownloadFragment(allocation, callback));
      return allocation;
   }

   public void submit() {
      if (!this.frameFragments.isEmpty()) {
         RawDownloadStream.DownloadFragment[] fragments = this.frameFragments.toArray(new RawDownloadStream.DownloadFragment[0]);
         this.frameFragments.clear();
         this.frames.add(new RawDownloadStream.DownloadFrame(new GlFence(), fragments));
      }
   }

   public void tick() {
      this.submit();

      while (!this.frames.isEmpty() && this.frames.peek().fence.signaled()) {
         RawDownloadStream.DownloadFrame frame = this.frames.poll();

         for (RawDownloadStream.DownloadFragment fragment : frame.fragments) {
            long addr = this.downloadBuffer.addr() + fragment.allocation;
            fragment.callback.accept(addr);
            this.allocationArena.free(fragment.allocation);
         }

         frame.fence.free();
      }
   }

   public int getBufferId() {
      return this.downloadBuffer.id;
   }

   public void free() {
      GL11.glFinish();
      this.tick();
      GlFence fence = new GlFence();

      while (!fence.signaled()) {
         GL11.glFinish();
      }

      fence.free();
      this.tick();
      if (this.frames.size() != 0) {
         throw new IllegalStateException();
      } else {
         this.frames.forEach(a -> a.fence.free());
         this.downloadBuffer.free();
      }
   }

   private record DownloadFragment(int allocation, RawDownloadStream.IDownloadCompletedCallback callback) {
   }

   private record DownloadFrame(GlFence fence, RawDownloadStream.DownloadFragment[] fragments) {
   }

   public interface IDownloadCompletedCallback {
      void accept(long var1);
   }
}
