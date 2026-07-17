package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.concurrent.locks.ReentrantLock;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;

public class GeometryCache {
   private final ReentrantLock lock = new ReentrantLock();
   private long maxCombinedSize;
   private long currentSize;
   private final Long2ObjectLinkedOpenHashMap<BuiltSection> cache = new Long2ObjectLinkedOpenHashMap();

   public GeometryCache(long maxSize) {
      this.setMaxTotalSize(maxSize);
   }

   public void setMaxTotalSize(long size) {
      this.maxCombinedSize = size;
   }

   public void put(BuiltSection section) {
      this.lock.lock();
      BuiltSection prev = (BuiltSection)this.cache.put(section.position, section);
      this.currentSize = this.currentSize + section.geometryBuffer.size;
      if (prev != null) {
         this.currentSize = this.currentSize - prev.geometryBuffer.size;
      }

      while (this.maxCombinedSize <= this.currentSize) {
         BuiltSection entry = (BuiltSection)this.cache.removeFirst();
         this.currentSize = this.currentSize - entry.geometryBuffer.size;
         entry.free();
      }

      this.lock.unlock();
      if (prev != null) {
         prev.free();
      }
   }

   public BuiltSection remove(long position) {
      this.lock.lock();
      BuiltSection section = (BuiltSection)this.cache.remove(position);
      if (section != null) {
         this.currentSize = this.currentSize - section.geometryBuffer.size;
      }

      this.lock.unlock();
      return section;
   }

   public void clear(long position) {
      BuiltSection sec = this.remove(position);
      if (sec != null) {
         sec.free();
      }
   }

   public void free() {
      this.lock.lock();
      this.cache.values().forEach(BuiltSection::free);
      this.cache.clear();
      this.lock.unlock();
   }
}
