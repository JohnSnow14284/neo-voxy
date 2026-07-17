package me.cortex.voxy.common.thread;

import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.Pair;

public class Service {
   private final PerThreadContextExecutor executor;
   private final ServiceManager sm;
   final long weight;
   final String name;
   final BooleanSupplier limiter;
   private final Semaphore tasks = new Semaphore(0);
   private volatile boolean isLive = true;
   private volatile boolean isStopping = false;

   Service(Supplier<Pair<Runnable, Runnable>> ctxSupplier, ServiceManager sm, long weight, String name, BooleanSupplier limiter) {
      this.sm = sm;
      this.weight = weight;
      this.name = name;
      this.limiter = limiter;
      this.executor = new PerThreadContextExecutor(ctxSupplier, e -> sm.handleException(this, e));
   }

   public void execute() {
      if (this.isStopping) {
         Logger.error("Tried executing on a dead service");
      } else {
         this.tasks.release();
         this.sm.execute(this);
      }
   }

   boolean runJob() {
      if (this.isStopping || !this.isLive) {
         return false;
      } else if (!this.tasks.tryAcquire()) {
         return false;
      } else if (!this.executor.run()) {
         throw new IllegalStateException("Executor failed to run");
      } else {
         return true;
      }
   }

   public boolean isLive() {
      return this.isLive && !this.isStopping;
   }

   public int numJobs() {
      return this.tasks.availablePermits();
   }

   public void blockTillEmpty() {
      while (this.isLive() && this.numJobs() != 0) {
         Thread.yield();

         try {
            Thread.sleep(10L);
         } catch (InterruptedException var2) {
            throw new RuntimeException(var2);
         }
      }
   }

   public int shutdown() {
      if (this.isStopping) {
         throw new IllegalStateException("Service not live");
      } else {
         this.isStopping = true;
         this.sm.removeService(this);
         this.executor.shutdown();
         int remaining = this.tasks.drainPermits();
         this.isLive = false;
         this.sm.remJobs(remaining);
         return remaining;
      }
   }

   public boolean steal() {
      if (!this.tasks.tryAcquire()) {
         return false;
      } else {
         this.sm.remJobs(1);
         return true;
      }
   }

   public int drain() {
      int tasks = this.tasks.drainPermits();
      if (tasks != 0) {
         this.sm.remJobs(tasks);
      }

      return tasks;
   }
}
