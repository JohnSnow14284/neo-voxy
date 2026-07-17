package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

public class GlBuffer extends TrackedObject {
   public final int id;
   private final long size;
   private final int flags;
   private static int COUNT;
   private static long TOTAL_SIZE;
   private static final long SCRATCH = MemoryUtil.nmemAlloc(4L);

   public GlBuffer(long size) {
      this(size, 0);
   }

   public GlBuffer(MemoryBuffer buffer) {
      this(buffer.size, 0, false, buffer.address);
   }

   public GlBuffer(long size, boolean zero) {
      this(size, 0, zero);
   }

   public GlBuffer(long size, int flags) {
      this(size, flags, true);
   }

   public GlBuffer(long size, int flags, boolean zero) {
      this(size, flags, zero, 0L);
   }

   private GlBuffer(long size, int flags, boolean zero, long data) {
      this.flags = flags;
      this.id = GL45C.glCreateBuffers();
      this.size = size;
      GL45C.nglNamedBufferStorage(this.id, size, data, flags);
      if ((flags & 1024) == 0 && zero) {
         this.zero();
      }

      COUNT++;
      TOTAL_SIZE += size;
   }

   @Override
   public void free() {
      this.free0();
      GL15.glDeleteBuffers(this.id);
      COUNT--;
      TOTAL_SIZE = TOTAL_SIZE - this.size;
   }

   public boolean isSparse() {
      return (this.flags & 1024) != 0;
   }

   public long size() {
      return this.size;
   }

   public GlBuffer zero() {
      GL45C.nglClearNamedBufferData(this.id, 33330, 36244, 5121, 0L);
      return this;
   }

   public GlBuffer zeroRange(long offset, long size) {
      GL45C.nglClearNamedBufferSubData(this.id, 33330, offset, size, 36244, 5121, 0L);
      return this;
   }

   public GlBuffer fill(int data) {
      GL45C.glPixelStorei(3315, 0);
      GL45C.glPixelStorei(3316, 0);
      MemoryUtil.memPutInt(SCRATCH, data);
      GL45C.nglClearNamedBufferData(this.id, 33334, 36244, 5125, SCRATCH);
      return this;
   }

   public static int getCount() {
      return COUNT;
   }

   public static long getTotalSize() {
      return TOTAL_SIZE;
   }

   public GlBuffer name(String name) {
      return GlDebug.name(name, this);
   }
}
