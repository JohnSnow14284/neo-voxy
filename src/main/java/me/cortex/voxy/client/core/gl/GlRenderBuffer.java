package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.opengl.GL45C;

public class GlRenderBuffer extends TrackedObject {
   public final int id = GL45C.glCreateRenderbuffers();

   public GlRenderBuffer(int format, int width, int height) {
      GL45C.glNamedRenderbufferStorage(this.id, format, width, height);
   }

   @Override
   public void free() {
      super.free0();
      GL45C.glDeleteRenderbuffers(this.id);
   }
}
