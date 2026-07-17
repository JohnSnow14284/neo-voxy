package me.cortex.voxy.common.thread;

import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import me.cortex.voxy.common.util.TrackedObject;

public class MultiThreadPrioritySemaphore {
   private final Semaphore pooledSemaphore = new Semaphore(0);
   private final IntSupplier executor;
   private volatile MultiThreadPrioritySemaphore.Block[] blocks = new MultiThreadPrioritySemaphore.Block[0];

   public MultiThreadPrioritySemaphore(IntSupplier executor) {
      this.executor = executor;
   }

   public synchronized MultiThreadPrioritySemaphore.Block createBlock() {
      MultiThreadPrioritySemaphore.Block block = new MultiThreadPrioritySemaphore.Block(this);
      MultiThreadPrioritySemaphore.Block[] blocks = Arrays.copyOf(this.blocks, this.blocks.length + 1);
      blocks[blocks.length - 1] = block;
      this.blocks = blocks;
      return block;
   }

   private synchronized void freeBlock(MultiThreadPrioritySemaphore.Block block) {
      MultiThreadPrioritySemaphore.Block[] ob = this.blocks;
      MultiThreadPrioritySemaphore.Block[] blocks = new MultiThreadPrioritySemaphore.Block[ob.length - 1];
      int j = 0;

      for (int i = 0; i <= blocks.length; i++) {
         if (ob[i] != block) {
            blocks[j++] = ob[i];
         }
      }

      if (j != blocks.length) {
         throw new IllegalStateException("Could not find the service in the services array");
      } else {
         this.blocks = blocks;
      }
   }

   public void pooledRelease(int permits) {
      this.pooledSemaphore.release(permits);

      for (MultiThreadPrioritySemaphore.Block block : this.blocks) {
         block.blockSemaphore.release(permits);
      }
   }

   private boolean tryRun(MultiThreadPrioritySemaphore.Block block) {
      if (!this.pooledSemaphore.tryAcquire()) {
         return false;
      } else {
         while (true) {
            int status = this.executor.getAsInt();
            if (status == 0) {
               return false;
            }

            if (status == 1) {
               return false;
            }

            if (2 <= status) {
               try {
                  if (block.localSemaphore.tryAcquire(10L, TimeUnit.MILLISECONDS)) {
                     block.blockSemaphore.tryAcquire();
                     this.pooledRelease(1);
                     return true;
                  }
               } catch (InterruptedException var4) {
                  throw new RuntimeException(var4);
               }
            }
         }
      }
   }

   public static final class Block extends TrackedObject {
      private final Semaphore blockSemaphore = new Semaphore(0);
      private final Semaphore localSemaphore = new Semaphore(0);
      private final MultiThreadPrioritySemaphore man;

      Block(MultiThreadPrioritySemaphore man) {
         this.man = man;
      }

      public void release(int permits) {
         this.localSemaphore.release(permits);
         this.blockSemaphore.release(permits);
      }

      public void acquire() {
         this.acquire(true);
      }

      public void acquire(boolean contributeToPool) {
         if (contributeToPool) {
            do {
               this.blockSemaphore.acquireUninterruptibly();
               if (this.localSemaphore.tryAcquire()) {
                  return;
               }
            } while (!this.man.tryRun(this));
         } else {
            this.localSemaphore.acquireUninterruptibly();
            this.blockSemaphore.tryAcquire();
         }
      }

      @Override
      public void free() {
         this.man.freeBlock(this);
         this.free0();
      }

      public int availablePermits() {
         return this.localSemaphore.availablePermits();
      }

      public boolean tryAcquire() {
         if (this.localSemaphore.availablePermits() == 0) {
            return false;
         } else if (!this.blockSemaphore.tryAcquire()) {
            return false;
         } else if (this.localSemaphore.tryAcquire()) {
            return true;
         } else {
            this.blockSemaphore.release(1);
            return false;
         }
      }
   }
}
