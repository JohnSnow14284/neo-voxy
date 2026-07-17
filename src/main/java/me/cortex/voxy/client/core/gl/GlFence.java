package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

public class GlFence extends TrackedObject {
   private final long fence = GL32.glFenceSync(37143, 0);
   private boolean signaled;
   private static final long SCRATCH = MemoryUtil.nmemCalloc(1L, 4L);

   public boolean signaled() {
      if (!this.signaled) {
         MemoryUtil.memPutInt(SCRATCH, -1);
         GL32.nglGetSynciv(this.fence, 37140, 1, 0L, SCRATCH);
         int val = MemoryUtil.memGetInt(SCRATCH);
         if (val == 37145) {
            this.signaled = true;
         } else if (val != 37144) {
            throw new IllegalStateException("Unknown data from glGetSync: " + val);
         }
      }

      return this.signaled;
   }

   @Override
   public void free() {
      super.free0();
      GL32.glDeleteSync(this.fence);
   }
}
