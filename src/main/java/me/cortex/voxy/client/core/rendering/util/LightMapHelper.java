package me.cortex.voxy.client.core.rendering.util;

import com.mojang.blaze3d.opengl.GlTexture;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL45;

public class LightMapHelper {
   private static final int LM_SAMPLER = GL45.glCreateSamplers();

   public static void bind(int lightingIndex) {
      GL33.glBindSampler(lightingIndex, LM_SAMPLER);
      GL45.glBindTextureUnit(lightingIndex, getLightmapTextureId());
   }

   public static int getLightmapTextureId() {
      return ((GlTexture)Minecraft.getInstance().gameRenderer.levelLightmap().texture()).glId();
   }

   static {
      GL33.glSamplerParameteri(LM_SAMPLER, 10241, 9729);
      GL33.glSamplerParameteri(LM_SAMPLER, 10240, 9729);
      GL33.glSamplerParameteri(LM_SAMPLER, 10242, 33071);
      GL33.glSamplerParameteri(LM_SAMPLER, 10243, 33071);
      GL33.glSamplerParameteri(LM_SAMPLER, 32882, 33071);
   }
}
