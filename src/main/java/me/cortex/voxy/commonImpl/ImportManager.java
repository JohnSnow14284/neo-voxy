package me.cortex.voxy.commonImpl;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.importers.IDataImporter;

public class ImportManager {
   private final Map<WorldEngine, ImportManager.ImportTask> activeImporters = new HashMap<>();

   protected synchronized ImportManager.ImportTask createImportTask(IDataImporter importer) {
      return new ImportManager.ImportTask(importer);
   }

   public boolean tryRunImport(IDataImporter importer) {
      ImportManager.ImportTask task;
      synchronized (this) {
         ImportManager.ImportTask importerTask = this.activeImporters.get(importer.getEngine());
         if (importerTask != null) {
            if (!importerTask.isCompleted()) {
               return false;
            }

            throw new IllegalStateException();
         }

         task = this.createImportTask(importer);
         this.activeImporters.put(importer.getEngine(), task);
      }

      task.start();
      return true;
   }

   public boolean makeAndRunIfNone(WorldEngine engine, Supplier<IDataImporter> factory) {
      try {
         engine.acquireRef();
         synchronized (this) {
            if (this.activeImporters.containsKey(engine)) {
               return false;
            }
         }

         return this.tryRunImport(factory.get());
      } finally {
         engine.releaseRef();
      }
   }

   public boolean cancelImport(WorldEngine engine) {
      ImportManager.ImportTask task;
      synchronized (this) {
         task = this.activeImporters.get(engine);
         if (task == null) {
            return false;
         }
      }

      task.shutdown();
      synchronized (this) {
         this.activeImporters.remove(engine);
         return true;
      }
   }

   private synchronized void jobFinished(ImportManager.ImportTask task) {
      ImportManager.ImportTask remTask = this.activeImporters.remove(task.importer.getEngine());
      if (remTask != null && remTask != task) {
         throw new IllegalStateException();
      }
   }

   protected class ImportTask {
      protected final IDataImporter importer;
      protected long startTime;
      protected long timer;
      protected long updateEvery;

      protected ImportTask(IDataImporter importer) {
         Objects.requireNonNull(ImportManager.this);
         super();
         this.updateEvery = 50L;
         this.importer = importer;
         this.timer = System.currentTimeMillis();
      }

      private void start() {
         if (this.importer.isRunning()) {
            throw new IllegalStateException();
         } else {
            this.startTime = System.currentTimeMillis();
            this.importer.runImport(this::onUpdate, this::onCompleted);
         }
      }

      protected boolean onUpdate(int completed, int outOf) {
         if (System.currentTimeMillis() - this.timer < this.updateEvery) {
            return false;
         } else {
            this.timer = System.currentTimeMillis();
            return true;
         }
      }

      protected void onCompleted(int total) {
         ImportManager.this.jobFinished(this);
      }

      protected void shutdown() {
         this.importer.shutdown();
      }

      protected boolean isCompleted() {
         return !this.importer.isRunning();
      }
   }
}
