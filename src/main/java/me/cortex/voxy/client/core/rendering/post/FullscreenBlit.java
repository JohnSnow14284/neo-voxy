package me.cortex.voxy.client.core.rendering.post;

import java.util.function.Consumer;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;

public class FullscreenBlit {
   private static final int EMPTY_VAO = GL45C.glCreateVertexArrays();
   private final Shader shader;

   public FullscreenBlit(RenderProperties properties, String fragId) {
      this(properties, fragId, b -> {});
   }

   public FullscreenBlit(RenderProperties properties, String vertId, String fragId) {
      this(properties, vertId, fragId, b -> {});
   }

   public <T extends Shader> FullscreenBlit(RenderProperties properties, String fragId, Consumer<Shader.Builder<T>> applyer) {
      this(properties, "voxy:post/fullscreen.vert", fragId, applyer);
   }

   public <T extends Shader> FullscreenBlit(RenderProperties properties, String vertId, String fragId, Consumer<Shader.Builder<T>> applyer) {
      this.shader = ((Shader.Builder<T>)Shader.make())
         .apply(properties::apply)
         .add(ShaderType.VERTEX, vertId)
         .add(ShaderType.FRAGMENT, fragId)
         .apply(applyer)
         .compile();
   }

   public void bind() {
      this.shader.bind();
   }

   public void blit() {
      GL30C.glBindVertexArray(EMPTY_VAO);
      this.shader.bind();
      GL15C.glBindBuffer(34963, SharedIndexBuffer.INSTANCE_BYTE.id());
      GL11C.glDrawElements(4, 6, 5121, 0L);
      GL30C.glBindVertexArray(0);
   }

   public void delete() {
      this.shader.free();
   }
}
