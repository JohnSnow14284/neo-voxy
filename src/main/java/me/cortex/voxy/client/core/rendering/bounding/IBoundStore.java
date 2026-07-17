package me.cortex.voxy.client.core.rendering.bounding;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;

public interface IBoundStore {
   GlBuffer getBuffer();

   int getCount();

   default void preRender(Viewport<?> viewport) {
   }

   default void postRender(Viewport<?> viewport) {
   }

   void free();

   static long transformBeforeStore(long pos) {
      return pos;
   }
}
