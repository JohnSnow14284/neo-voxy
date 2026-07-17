package me.cortex.voxy.client.core.rendering.section.geometry;

import java.util.function.Consumer;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;

public abstract class AbstractSectionGeometryManager implements IGeometryManager {
   public final int maxSections;
   public final long geometryCapacity;

   protected AbstractSectionGeometryManager(int maxSections, long geometryCapacity) {
      if ((maxSections & maxSections - 1) != 0) {
         throw new IllegalArgumentException("Max sections should be a power of 2");
      } else {
         this.maxSections = maxSections;
         this.geometryCapacity = geometryCapacity;
      }
   }

   @Override
   public int uploadSection(BuiltSection section) {
      return this.uploadReplaceSection(-1, section);
   }

   @Override
   public abstract int uploadReplaceSection(int var1, BuiltSection var2);

   @Override
   public abstract void removeSection(int var1);

   public void tick() {
   }

   public void free() {
   }

   @Override
   public abstract void downloadAndRemove(int var1, Consumer<BuiltSection> var2);

   public abstract long getUsedCapacity();

   public long getRemainingCapacity() {
      return this.geometryCapacity - this.getUsedCapacity();
   }
}
