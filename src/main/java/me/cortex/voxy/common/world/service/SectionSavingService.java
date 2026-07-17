package me.cortex.voxy.common.world.service;

import java.util.concurrent.ConcurrentLinkedDeque;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

public class SectionSavingService {
   private static final int SOFT_MAX_QUEUE_SIZE = 5000;
   private final Service service;
   private final ConcurrentLinkedDeque<SectionSavingService.SaveEntry> saveQueue = new ConcurrentLinkedDeque<>();

   public SectionSavingService(ServiceManager sm) {
      this.service = sm.createServiceNoCleanup(() -> this::processJob, 100L, "Section saving service");
   }

   private void processJob() {
      SectionSavingService.SaveEntry task = this.saveQueue.pop();
      WorldSection section = task.section;
      section.assertNotFree();

      try {
         if (section.exchangeIsInSaveQueue(false)) {
            section.setNotDirty();
            task.engine.storage.saveSection(section);
         } else {
            section.setNotDirty();
         }
      } catch (Throwable var7) {
         section.exchangeIsInSaveQueue(false);
         section.setNotDirty();
         Logger.error("Voxy saver had an exception while executing please check logs and report error", var7);
      } finally {
         section.release();
      }
   }

   public boolean enqueueSave(WorldEngine in, WorldSection section, boolean nonBlocking, boolean sectionAlreadyAcquired) {
      if (section.exchangeIsInSaveQueue(true)) {
         if (!sectionAlreadyAcquired) {
            section.acquire();
         }

         if (!nonBlocking && this.getTaskCount() > 5000) {
            Thread.yield();

            while (this.getTaskCount() > 5000 && this.service.isLive() && this.service.steal()) {
               this.processJob();
            }
         }

         this.saveQueue.add(new SectionSavingService.SaveEntry(in, section));
         this.service.execute();
         return true;
      } else {
         return false;
      }
   }

   public void shutdown() {
      if (this.service.numJobs() != 0) {
         Logger.info("Finishing " + this.service.numJobs() + " queued Voxy section saves before shutdown.");

         while (this.service.steal()) {
            this.processJob();
         }
      }

      this.service.shutdown();

      while (!this.saveQueue.isEmpty()) {
         this.processJob();
      }
   }

   public int getTaskCount() {
      return this.service.numJobs();
   }

   private record SaveEntry(WorldEngine engine, WorldSection section) {
   }
}
