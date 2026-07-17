package me.cortex.voxy.client.mixin.sodium;

import java.util.concurrent.Semaphore;
import me.cortex.voxy.client.compat.SemaphoreBlockImpersonator;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.thread.MultiThreadPrioritySemaphore;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobQueue", remap = false)
public class MixinChunkJobQueue {
   @Unique
   private MultiThreadPrioritySemaphore.Block voxy$semaphoreBlock;

   @Redirect(method = "<init>", at = @At(value = "NEW", target = "(I)Ljava/util/concurrent/Semaphore;"))
   private Semaphore voxy$injectUnifiedPool(int permits) {
      VoxyInstance instance = VoxyCommon.getInstance();
      if (instance != null && !VoxyConfig.CONFIG.dontUseSodiumBuilderThreads) {
         this.voxy$semaphoreBlock = instance.getThreadPool().groupSemaphore.createBlock();
         return new SemaphoreBlockImpersonator(this.voxy$semaphoreBlock);
      } else {
         return new Semaphore(permits);
      }
   }

   @Inject(method = "shutdown", at = @At("RETURN"))
   private void voxy$injectAtShutdown(CallbackInfoReturnable ci) {
      if (this.voxy$semaphoreBlock != null) {
         this.voxy$semaphoreBlock.free();
      }
   }
}
