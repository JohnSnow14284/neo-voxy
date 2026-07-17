package me.cortex.voxy.client.mixin.minecraft.util;

import me.cortex.voxy.client.GPUSelectorWindows2;
import me.cortex.voxy.common.util.ThreadUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinGPUSelect {
   @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;save()V", ordinal = 0))
   private void voxy$injectInitWindow(GameConfig gc, CallbackInfo ci) {
      String prop = System.getProperty("voxy.forceGpuSelectionIndex", "NO");
      if (!prop.equals("NO")) {
         GPUSelectorWindows2.doSelector(Integer.parseInt(prop));
      }

      Thread.currentThread().setPriority(10);
      ThreadUtils.SetSelfThreadPriorityWin32(15);
   }
}
