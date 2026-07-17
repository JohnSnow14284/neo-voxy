package me.cortex.voxy.client.core.util;

import it.unimi.dsi.fastutil.ints.Int2ObjectFunction;
import me.cortex.voxy.common.util.HierarchicalBitSet;

public class ExpandingObjectAllocationList<T> {
   private static final float GROWTH_FACTOR = 0.75F;
   private final Int2ObjectFunction<T[]> arrayGenerator;
   private final HierarchicalBitSet bitSet;
   private T[] objects;

   public ExpandingObjectAllocationList(Int2ObjectFunction<T[]> arrayGenerator) {
      this(arrayGenerator, -1);
   }

   public ExpandingObjectAllocationList(Int2ObjectFunction<T[]> arrayGenerator, int limit) {
      this.arrayGenerator = arrayGenerator;
      this.objects = (T[])((Object[])this.arrayGenerator.apply(16));
      if (limit != -1) {
         this.bitSet = new HierarchicalBitSet(limit);
      } else {
         this.bitSet = new HierarchicalBitSet();
      }
   }

   public int put(T obj) {
      int id = this.bitSet.allocateNext();
      if (id < 0) {
         throw new IllegalStateException("Id over max request capacity");
      } else {
         if (this.objects.length <= id) {
            int newLen = this.objects.length + (int)Math.ceil(this.objects.length * 0.75F);
            T[] newArr = (T[])((Object[])this.arrayGenerator.apply(newLen));
            System.arraycopy(this.objects, 0, newArr, 0, this.objects.length);
            this.objects = newArr;
         }

         this.objects[id] = obj;
         return id;
      }
   }

   public void release(int id) {
      if (!this.bitSet.free(id)) {
         throw new IllegalArgumentException("Index " + id + " was already released");
      } else {
         this.objects[id] = null;
      }
   }

   public T get(int index) {
      if (!this.bitSet.isSet(index)) {
         throw new IllegalArgumentException("Index " + index + " is not allocated");
      } else {
         return this.objects[index];
      }
   }

   public int count() {
      return this.bitSet.getCount();
   }
}
