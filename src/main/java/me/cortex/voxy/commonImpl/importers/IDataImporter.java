package me.cortex.voxy.commonImpl.importers;

import me.cortex.voxy.common.world.WorldEngine;

public interface IDataImporter {
   void runImport(IDataImporter.IUpdateCallback var1, IDataImporter.ICompletionCallback var2);

   WorldEngine getEngine();

   void shutdown();

   boolean isRunning();

   public interface ICompletionCallback {
      void onCompletion(int var1);
   }

   public interface IUpdateCallback {
      void onUpdate(int var1, int var2);
   }
}
