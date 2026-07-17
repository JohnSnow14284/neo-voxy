package me.cortex.voxy.client.core.gl.shader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlDebug;
import me.cortex.voxy.client.core.gl.GlTexture;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

public class AutoBindingShader extends Shader {
   private final Map<String, String> defines;
   private final List<AutoBindingShader.BufferBinding> bindings = new ArrayList<>();
   private final List<AutoBindingShader.TextureBinding> textureBindings = new ArrayList<>();
   private boolean rebuild = true;

   AutoBindingShader(Shader.Builder<AutoBindingShader> builder, int program) {
      super(program);
      this.defines = builder.defines;
   }

   public AutoBindingShader name(String name) {
      return GlDebug.name(name, this);
   }

   public AutoBindingShader ssboIf(String define, GlBuffer buffer) {
      return this.defines.containsKey(define) ? this.ssbo(define, buffer) : this;
   }

   public AutoBindingShader ssbo(int index, GlBuffer binding) {
      return this.ssbo(index, binding, 0L);
   }

   public AutoBindingShader ssbo(String define, GlBuffer binding) {
      return this.ssbo(Integer.parseInt(this.defines.get(define)), binding, 0L);
   }

   public AutoBindingShader ssbo(int index, GlBuffer buffer, long offset) {
      this.insertOrReplaceBinding(new AutoBindingShader.BufferBinding(37074, index, buffer, offset, -1L));
      return this;
   }

   public AutoBindingShader ubo(String define, GlBuffer buffer) {
      return this.ubo(Integer.parseInt(this.defines.get(define)), buffer);
   }

   public AutoBindingShader ubo(int index, GlBuffer buffer) {
      return this.ubo(index, buffer, 0L);
   }

   public AutoBindingShader ubo(int index, GlBuffer buffer, long offset) {
      this.insertOrReplaceBinding(new AutoBindingShader.BufferBinding(35345, index, buffer, offset, -1L));
      return this;
   }

   private void insertOrReplaceBinding(AutoBindingShader.BufferBinding binding) {
      this.rebuild = true;

      for (int i = 0; i < this.bindings.size(); i++) {
         AutoBindingShader.BufferBinding entry = this.bindings.get(i);
         if (entry.target == binding.target && entry.index == binding.index) {
            this.bindings.set(i, binding);
            return;
         }
      }

      this.bindings.add(binding);
   }

   public AutoBindingShader texture(String define, GlTexture texture) {
      return this.texture(define, -1, texture);
   }

   public AutoBindingShader texture(String define, int sampler, GlTexture texture) {
      return this.texture(Integer.parseInt(this.defines.get(define)), sampler, texture);
   }

   public AutoBindingShader texture(int unit, int sampler, GlTexture texture) {
      this.rebuild = true;

      for (int i = 0; i < this.textureBindings.size(); i++) {
         AutoBindingShader.TextureBinding entry = this.textureBindings.get(i);
         if (entry.unit == unit) {
            this.textureBindings.set(i, new AutoBindingShader.TextureBinding(unit, sampler, texture));
            return this;
         }
      }

      this.textureBindings.add(new AutoBindingShader.TextureBinding(unit, sampler, texture));
      return this;
   }

   @Override
   public void bind() {
      super.bind();
      if (!this.bindings.isEmpty()) {
         for (AutoBindingShader.BufferBinding binding : this.bindings) {
            binding.buffer.assertNotFreed();
            if (binding.offset == 0L && binding.size == -1L) {
               GL30.glBindBufferBase(binding.target, binding.index, binding.buffer.id);
            } else {
               GL30.glBindBufferRange(binding.target, binding.index, binding.buffer.id, binding.offset, binding.size);
            }
         }
      }

      if (!this.textureBindings.isEmpty()) {
         for (AutoBindingShader.TextureBinding bindingx : this.textureBindings) {
            if (bindingx.texture != null) {
               bindingx.texture.assertNotFreed();
               ARBDirectStateAccess.glBindTextureUnit(bindingx.unit, bindingx.texture.id);
            }

            if (bindingx.sampler != -1) {
               GL33.glBindSampler(bindingx.unit, bindingx.sampler);
            }
         }
      }
   }

   private record BufferBinding(int target, int index, GlBuffer buffer, long offset, long size) {
   }

   private record TextureBinding(int unit, int sampler, GlTexture texture) {
   }
}
