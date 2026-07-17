package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.client.core.gl.shader.Shader;
import org.lwjgl.opengl.GL43C;

public class GlDebug {
   public static final boolean GL_DEBUG = System.getProperty("voxy.glDebug", "false").equals("true");

   public static void push() {
   }

   public static GlBuffer name(String name, GlBuffer buffer) {
      if (GL_DEBUG) {
         GL43C.glObjectLabel(33504, buffer.id, name);
      }

      return buffer;
   }

   public static <T extends Shader> T name(String name, T shader) {
      if (GL_DEBUG) {
         GL43C.glObjectLabel(33506, shader.id(), name);
      }

      return shader;
   }

   public static GlFramebuffer name(String name, GlFramebuffer framebuffer) {
      if (GL_DEBUG) {
         GL43C.glObjectLabel(36160, framebuffer.id, name);
      }

      return framebuffer;
   }

   public static GlTexture name(String name, GlTexture texture) {
      if (GL_DEBUG) {
         GL43C.glObjectLabel(5890, texture.id, name);
      }

      return texture;
   }

   public static GlPersistentMappedBuffer name(String name, GlPersistentMappedBuffer buffer) {
      if (GL_DEBUG) {
         GL43C.glObjectLabel(33504, buffer.id, name);
      }

      return buffer;
   }
}
