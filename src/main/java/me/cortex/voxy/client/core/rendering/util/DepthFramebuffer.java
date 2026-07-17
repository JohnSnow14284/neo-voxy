package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

public class DepthFramebuffer {
   private final int depthType;
   private GlTexture depthBuffer;
   public final GlFramebuffer framebuffer = new GlFramebuffer();

   public DepthFramebuffer() {
      this(33190);
   }

   public DepthFramebuffer(int depthType) {
      this.depthType = depthType;
   }

   public boolean resize(int width, int height) {
      if (this.depthBuffer != null && this.depthBuffer.getWidth() == width && this.depthBuffer.getHeight() == height) {
         return false;
      } else {
         if (this.depthBuffer != null) {
            this.depthBuffer.free();
         }

         this.depthBuffer = new GlTexture().store(this.depthType, 1, width, height);
         this.framebuffer.bind(this.getDepthAttachmentType(), this.depthBuffer).verify();
         return true;
      }
   }

   public int getDepthAttachmentType() {
      return this.depthType == 35056 ? 33306 : 36096;
   }

   public void clear(float depth) {
      MemoryStack stack = MemoryStack.stackPush();

      try {
         ARBDirectStateAccess.nglClearNamedFramebufferfv(this.framebuffer.id, 6145, 0, stack.nfloat(depth));
      } catch (Throwable var6) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var5) {
               var6.addSuppressed(var5);
            }
         }

         throw var6;
      }

      if (stack != null) {
         stack.close();
      }
   }

   public void clearStencil(int to) {
      MemoryStack stack = MemoryStack.stackPush();

      try {
         ARBDirectStateAccess.nglClearNamedFramebufferiv(this.framebuffer.id, 6146, 0, stack.nint(to));
      } catch (Throwable var6) {
         if (stack != null) {
            try {
               stack.close();
            } catch (Throwable var5) {
               var6.addSuppressed(var5);
            }
         }

         throw var6;
      }

      if (stack != null) {
         stack.close();
      }
   }

   public GlTexture getDepthTex() {
      return this.depthBuffer;
   }

   public void free() {
      this.framebuffer.free();
      if (this.depthBuffer != null) {
         this.depthBuffer.free();
      }
   }

   public void bind() {
      GL30C.glBindFramebuffer(36160, this.framebuffer.id);
   }

   public int getFormat() {
      return this.depthType;
   }
}
