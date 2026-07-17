package me.cortex.voxy.client.core;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

public interface IVoxyRenderSystemHolder {
   VoxyRenderSystem voxy$getRenderSystem();

   void voxy$shutdownRenderer();

   void voxy$createRenderer();

   void voxy$setWorld(Level var1);

   static VoxyRenderSystem getNullable() {
      IVoxyRenderSystemHolder lr = getNullableHolder();
      return lr == null ? null : lr.voxy$getRenderSystem();
   }

   static IVoxyRenderSystemHolder getNullableHolder() {
      return (IVoxyRenderSystemHolder)Minecraft.getInstance().levelRenderer;
   }
}
