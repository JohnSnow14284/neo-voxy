package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL45C;

public class GlPersistentMappedBuffer extends TrackedObject {
   public final int id = GL45C.glCreateBuffers();
   private final long size;
   private final long addr;

   public GlPersistentMappedBuffer(long size, int flags) {
      this.size = size;
      GL45C.glNamedBufferStorage(this.id, size, 64 | flags & 643);
      this.addr = GL45C.nglMapNamedBufferRange(this.id, 0L, size, flags & 51 | 64);
   }

   @Override
   public void free() {
      this.free0();
      GL45C.glUnmapNamedBuffer(this.id);
      GL15.glDeleteBuffers(this.id);
   }

   public long size() {
      return this.size;
   }

   public long addr() {
      return this.addr;
   }

   public GlPersistentMappedBuffer name(String name) {
      return GlDebug.name(name, this);
   }
}
