package me.cortex.voxy.common.config.storage;

import java.util.ArrayList;
import java.util.List;
import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.config.IStoredSectionPositionIterator;
import me.cortex.voxy.common.util.MemoryBuffer;

public abstract class StorageBackend implements IMappingStorage, IStoredSectionPositionIterator {
   public abstract MemoryBuffer getSectionData(long var1, MemoryBuffer var3);

   public abstract void setSectionData(long var1, MemoryBuffer var3);

   public abstract void deleteSectionData(long var1);

   @Override
   public abstract void flush();

   @Override
   public abstract void close();

   public List<StorageBackend> getChildBackends() {
      return List.of();
   }

   public final List<StorageBackend> collectAllBackends() {
      List<StorageBackend> backends = new ArrayList<>();
      backends.add(this);

      for (StorageBackend child : this.getChildBackends()) {
         backends.addAll(child.collectAllBackends());
      }

      return backends;
   }
}
