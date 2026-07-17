package me.cortex.voxy.commonImpl;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.locks.StampedLock;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.thread.UnifiedServiceThreadPool;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.service.SectionSavingService;
import me.cortex.voxy.common.world.service.VoxelIngestService;

public abstract class VoxyInstance {
   private volatile boolean isRunning = true;
   private final Thread worldCleaner;
   public final BooleanSupplier savingServiceRateLimiter;
   protected final UnifiedServiceThreadPool threadPool;
   protected final SectionSavingService savingService;
   protected final VoxelIngestService ingestService;
   private final StampedLock activeWorldLock = new StampedLock();
   private final HashMap<WorldIdentifier, WorldEngine> activeWorlds = new HashMap<>();
   protected final ImportManager importManager;

   public VoxyInstance() {
      if (!this.shouldCreateInstance()) {
         throw new DontCreateInstance();
      } else {
         Logger.info("Initializing voxy instance");
         this.threadPool = new UnifiedServiceThreadPool();
         this.savingService = new SectionSavingService(this.getServiceManager());
         this.ingestService = new VoxelIngestService(this.getServiceManager());
         this.importManager = this.createImportManager();
         this.savingServiceRateLimiter = () -> this.savingService.getTaskCount() < 1200;
         this.worldCleaner = new Thread(() -> {
            try {
               while (this.isRunning) {
                  Thread.sleep(1000L);
                  this.cleanIdle();
               }
            } catch (InterruptedException var2) {
            } catch (Exception var3) {
               Logger.error("Exception in world cleaner", var3);
            }
         });
         this.worldCleaner.setPriority(1);
         this.worldCleaner.setName("Active world cleaner");
         this.worldCleaner.setDaemon(true);
         this.worldCleaner.start();
      }
   }

   protected boolean shouldCreateInstance() {
      return true;
   }

   protected void setNumThreads(int threads) {
      if (threads < 0) {
         throw new IllegalArgumentException("Num threads <0");
      } else {
         if (this.threadPool.setNumThreads(threads)) {
            Logger.info("Dedicated voxy thread pool size: " + threads);
         }
      }
   }

   public void updateDedicatedThreads() {
      this.setNumThreads(3);
   }

   protected ImportManager createImportManager() {
      return new ImportManager();
   }

   public ServiceManager getServiceManager() {
      return this.threadPool.serviceManager;
   }

   public UnifiedServiceThreadPool getThreadPool() {
      return this.threadPool;
   }

   public VoxelIngestService getIngestService() {
      return this.ingestService;
   }

   public ImportManager getImportManager() {
      return this.importManager;
   }

   public WorldEngine getNullable(WorldIdentifier identifier) {
      if (!this.isRunning) {
         return null;
      } else {
         WeakReference<WorldEngine> cache = identifier.cachedEngineObject;
         WorldEngine world;
         if (cache == null) {
            world = null;
         } else {
            world = cache.get();
            if (world == null) {
               identifier.cachedEngineObject = null;
            } else if (world.isLive()) {
               if (world.instanceIn != this) {
                  throw new IllegalStateException("World cannot be in identifier cache, alive and not part of this instance");
               }
            } else {
               identifier.cachedEngineObject = null;
               world = null;
            }
         }

         if (world == null) {
            long stamp = this.activeWorldLock.readLock();
            world = this.activeWorlds.get(identifier);
            this.activeWorldLock.unlockRead(stamp);
            if (world != null) {
               identifier.cachedEngineObject = new WeakReference<>(world);
            }
         }

         if (world != null) {
            world.markActive();
         }

         return world;
      }
   }

   public WorldEngine getOrCreate(WorldIdentifier identifier) {
      return this.getOrCreate(identifier, false);
   }

   public WorldEngine getOrCreate(WorldIdentifier identifier, boolean incrementRef) {
      if (!this.isRunning) {
         Logger.error("Tried getting world object on voxy instance but its not running");
         return null;
      } else {
         WorldEngine world = this.getNullable(identifier);
         if (world != null) {
            world.markActive();
            if (incrementRef) {
               world.acquireRef();
            }

            return world;
         } else {
            long stamp = this.activeWorldLock.writeLock();
            if (!this.isRunning) {
               Logger.error("Tried getting world object on voxy instance but its not running");
               this.activeWorldLock.unlockWrite(stamp);
               return null;
            } else {
               world = this.activeWorlds.get(identifier);
               if (world == null) {
                  world = this.createWorld(identifier);
               }

               world.markActive();
               if (incrementRef) {
                  world.acquireRef();
               }

               this.activeWorldLock.unlockWrite(stamp);
               identifier.cachedEngineObject = new WeakReference<>(world);
               return world;
            }
         }
      }
   }

   protected abstract SectionStorage createStorage(WorldIdentifier var1);

   private WorldEngine createWorld(WorldIdentifier identifier) {
      if (!this.isRunning) {
         throw new IllegalStateException("Cannot create world while not running");
      } else if (this.activeWorlds.containsKey(identifier)) {
         throw new IllegalStateException("Existing world with identifier");
      } else {
         Logger.info("Creating new world engine: " + identifier.getLongHash() + "@" + System.identityHashCode(this));
         WorldEngine world = new WorldEngine(this.createStorage(identifier), this);
         world.setSaveCallback(this.savingService::enqueueSave);
         this.activeWorlds.put(identifier, world);
         return world;
      }
   }

   public void cleanIdle() {
      List<WorldIdentifier> idleWorlds = null;
      long stamp = this.activeWorldLock.readLock();

      for (Entry<WorldIdentifier, WorldEngine> pair : this.activeWorlds.entrySet()) {
         if (pair.getValue().isWorldIdle()) {
            if (idleWorlds == null) {
               idleWorlds = new ArrayList<>();
            }

            idleWorlds.add(pair.getKey());
         }
      }

      this.activeWorldLock.unlockRead(stamp);
      if (idleWorlds != null) {
         stamp = this.activeWorldLock.writeLock();

         for (WorldIdentifier id : idleWorlds) {
            WorldEngine world = this.activeWorlds.remove(id);
            if (world != null) {
               if (!world.isWorldIdle()) {
                  this.activeWorlds.put(id, world);
               } else {
                  Logger.info("Shutting down idle world: " + id.getLongHash());
                  world.free();
               }
            }
         }

         this.activeWorldLock.unlockWrite(stamp);
      }
   }

   public void addDebug(List<String> debug) {
      debug.add("MemoryBuffer, Count/Size (mb): " + MemoryBuffer.getCount() + "/" + MemoryBuffer.getTotalSize() / 1000000L);
      debug.add(
         "I/S/AWSC: "
            + this.ingestService.getTaskCount()
            + "/"
            + this.savingService.getTaskCount()
            + "/["
            + this.activeWorlds.values().stream().map(a -> a.getActiveSectionCount() + "").collect(Collectors.joining(", "))
            + "]"
      );
   }

   public void shutdown() {
      Logger.info("Shutting down voxy instance");
      this.isRunning = false;

      try {
         this.worldCleaner.join();
      } catch (InterruptedException var11) {
         throw new RuntimeException(var11);
      }

      this.cleanIdle();
      if (!this.activeWorlds.isEmpty()) {
         long stamp = this.activeWorldLock.readLock();

         for (WorldEngine world : this.activeWorlds.values()) {
            this.importManager.cancelImport(world);
         }

         this.activeWorldLock.unlockRead(stamp);
      }

      try {
         this.ingestService.shutdown();
      } catch (Exception var10) {
         Logger.error(var10);
      }

      try {
         this.savingService.shutdown();
      } catch (Exception var9) {
         Logger.error(var9);
      }

      long stamp = this.activeWorldLock.writeLock();
      if (!this.activeWorlds.isEmpty()) {
         boolean printedNotice = false;

         for (WorldEngine world : new ArrayList<>(this.activeWorlds.values())) {
            if (world.isWorldUsed()) {
               if (!printedNotice) {
                  printedNotice = true;
                  Logger.error("Not all worlds shutdown, force closing worlds");
               }

               this.activeWorldLock.unlockWrite(stamp);

               while (world.isWorldUsed()) {
                  try {
                     Thread.sleep(10L);
                  } catch (InterruptedException var8) {
                     throw new RuntimeException(var8);
                  }
               }

               stamp = this.activeWorldLock.writeLock();
            }

            world.free();
         }

         this.activeWorlds.clear();
      }

      try {
         this.threadPool.shutdown();
      } catch (Exception var7) {
         Logger.error(var7);
      }

      if (!this.activeWorlds.isEmpty()) {
         throw new IllegalStateException("Not all worlds shutdown");
      } else {
         Logger.info("Instance shutdown");
         this.activeWorldLock.unlockWrite(stamp);
      }
   }

   public boolean isIngestEnabled(WorldIdentifier worldId) {
      return true;
   }

   public boolean isRunning() {
      return this.isRunning;
   }
}
