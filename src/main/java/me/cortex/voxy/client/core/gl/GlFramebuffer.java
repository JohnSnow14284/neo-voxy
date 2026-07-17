package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.opengl.GL45C;

public class GlFramebuffer extends TrackedObject {
   public final int id = GL45C.glCreateFramebuffers();

   public GlFramebuffer bind(int attachment, GlTexture texture) {
      return this.bind(attachment, texture, 0);
   }

   public GlFramebuffer bind(int attachment, int texture) {
      return this.bind(attachment, texture, 0);
   }

   public GlFramebuffer bind(int attachment, int texture, int lvl) {
      GL45C.glNamedFramebufferTexture(this.id, attachment, texture, lvl);
      return this;
   }

   public GlFramebuffer bind(int attachment, GlTexture texture, int lvl) {
      GL45C.glNamedFramebufferTexture(this.id, attachment, texture.id, lvl);
      return this;
   }

   public GlFramebuffer bind(int attachment, GlRenderBuffer buffer) {
      GL45C.glNamedFramebufferRenderbuffer(this.id, attachment, 36161, buffer.id);
      return this;
   }

   public GlFramebuffer setDrawBuffers(int... buffers) {
      GL45C.glNamedFramebufferDrawBuffers(this.id, buffers);
      return this;
   }

   @Override
   public void free() {
      super.free0();
      GL45C.glDeleteFramebuffers(this.id);
   }

   public GlFramebuffer verify() {
      int code;
      if ((code = GL45C.glCheckNamedFramebufferStatus(this.id, 36160)) != 36053) {
         throw new IllegalStateException("Framebuffer incomplete with error code: " + code);
      } else {
         return this;
      }
   }

   public GlFramebuffer name(String name) {
      return GlDebug.name(name, this);
   }
}
