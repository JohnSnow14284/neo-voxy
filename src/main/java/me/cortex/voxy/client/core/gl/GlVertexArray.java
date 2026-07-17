package me.cortex.voxy.client.core.gl;

import java.util.Arrays;
import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45C;

public class GlVertexArray extends TrackedObject {
   public static final int STATIC_VAO = GL30.glGenVertexArrays();
   public final int id;
   private int[] indices = new int[0];
   private int stride;

   public GlVertexArray() {
      this.id = GL45C.glCreateVertexArrays();
   }

   @Override
   public void free() {
      this.free0();
      GL45C.glDeleteVertexArrays(this.id);
   }

   public void bind() {
      GL45C.glBindVertexArray(this.id);
   }

   public GlVertexArray bindBuffer(int buffer) {
      for (int index : this.indices) {
         GL45C.glVertexArrayVertexBuffer(this.id, index, buffer, 0L, this.stride);
      }

      return this;
   }

   public GlVertexArray bindElementBuffer(int buffer) {
      GL45C.glVertexArrayElementBuffer(this.id, buffer);
      return this;
   }

   public GlVertexArray setStride(int stride) {
      this.stride = stride;
      return this;
   }

   public GlVertexArray setI(int index, int type, int count, int offset) {
      this.addIndex(index);
      GL45C.glEnableVertexArrayAttrib(this.id, index);
      GL45C.glVertexArrayAttribIFormat(this.id, index, count, type, offset);
      return this;
   }

   public GlVertexArray setF(int index, int type, int count, int offset) {
      return this.setF(index, type, count, false, offset);
   }

   public GlVertexArray setF(int index, int type, int count, boolean normalize, int offset) {
      this.addIndex(index);
      GL45C.glEnableVertexArrayAttrib(this.id, index);
      GL45C.glVertexArrayAttribFormat(this.id, index, count, type, normalize, offset);
      return this;
   }

   private void addIndex(int index) {
      for (int i : this.indices) {
         if (i == index) {
            return;
         }
      }

      this.indices = Arrays.copyOf(this.indices, this.indices.length + 1);
      this.indices[this.indices.length - 1] = index;
   }
}
