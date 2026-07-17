package me.cortex.voxy.common.util;

import java.lang.ref.Cleaner.Cleanable;

public class ResizingThreadLocalMemoryBuffer {
   private final ThreadLocal<Pair<Cleanable, MemoryBuffer>> threadLocal;

   private static Pair<Cleanable, MemoryBuffer> createMemoryBuffer(long initalSize) {
      MemoryBuffer buffer = new MemoryBuffer(initalSize);
      MemoryBuffer ref = MemoryBuffer.createUntrackedUnfreeableRawFrom(buffer.address, buffer.size);
      Cleanable cleanable = GlobalCleaner.CLEANER.register(ref, buffer::free);
      return new Pair<>(cleanable, ref);
   }

   public ResizingThreadLocalMemoryBuffer(long initalSize) {
      this.threadLocal = ThreadLocal.withInitial(() -> createMemoryBuffer(initalSize));
   }

   public MemoryBuffer get() {
      return this.threadLocal.get().right();
   }

   public MemoryBuffer get(long minSize) {
      Pair<Cleanable, MemoryBuffer> current = this.threadLocal.get();
      if (current.right().size < minSize) {
         current.left().clean();
         current = createMemoryBuffer(minSize + 1024L);
         this.threadLocal.set(current);
      }

      return current.right();
   }
}
