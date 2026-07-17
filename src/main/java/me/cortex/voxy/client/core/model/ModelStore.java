package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL45;

public class ModelStore {
   public static final int MODEL_SIZE = 64;
   final GlBuffer modelBuffer;
   final GlBuffer modelColourBuffer;
   final GlTexture textures;
   public final int blockSampler = GL33.glGenSamplers();

   public ModelStore() {
      this.modelBuffer = new GlBuffer(4194304L).name("ModelData");
      this.modelColourBuffer = new GlBuffer(262144L).name("ModelColour");
      this.textures = RenderResourceReuse.getOrCreateModelStoreTextureAtlas();
      int mipLvl = ((TextureAtlas)Minecraft.getInstance()
            .getTextureManager()
            .getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")))
         .maxMipLevel;
      GL33C.glSamplerParameteri(this.blockSampler, 10241, 9986);
      GL33C.glSamplerParameteri(this.blockSampler, 10240, 9728);
      GL33C.glSamplerParameteri(this.blockSampler, 33082, 0);
      GL33C.glSamplerParameteri(this.blockSampler, 33083, mipLvl);
   }

   public void free() {
      this.modelBuffer.free();
      this.modelColourBuffer.free();
      RenderResourceReuse.giveBackModelStoreTextureAtlas(this.textures);
      GL33.glDeleteSamplers(this.blockSampler);
   }

   public void bind(int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
      GL30.glBindBufferBase(37074, modelBindingIndex, this.modelBuffer.id);
      GL30.glBindBufferBase(37074, colourBindingIndex, this.modelColourBuffer.id);
      GL45.glBindTextureUnit(textureBindingIndex, this.textures.id);
      GL33.glBindSampler(textureBindingIndex, this.blockSampler);
   }
}
