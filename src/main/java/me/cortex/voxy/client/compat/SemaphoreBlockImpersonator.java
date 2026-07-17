package me.cortex.voxy.client.compat;

import java.util.concurrent.Semaphore;
import me.cortex.voxy.common.thread.MultiThreadPrioritySemaphore;

public class SemaphoreBlockImpersonator extends Semaphore {
   private final MultiThreadPrioritySemaphore.Block block;

   public SemaphoreBlockImpersonator(MultiThreadPrioritySemaphore.Block block) {
      super(0);
      this.block = block;
   }

   @Override
   public void release(int permits) {
      this.block.release(permits);
   }

   @Override
   public void acquire() throws InterruptedException {
      this.block.acquire();
   }

   @Override
   public boolean tryAcquire() {
      return this.block.tryAcquire();
   }

   @Override
   public int availablePermits() {
      return this.block.availablePermits();
   }
}
