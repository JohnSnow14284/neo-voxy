package me.cortex.voxy.common.util;

public class ThreadLocalMemoryBuffer {
   private final ThreadLocal<MemoryBuffer> threadLocal;

   private static MemoryBuffer createMemoryBuffer(long size) {
      MemoryBuffer buffer = new MemoryBuffer(size);
      MemoryBuffer ref = MemoryBuffer.createUntrackedUnfreeableRawFrom(buffer.address, buffer.size);
      GlobalCleaner.CLEANER.register(ref, buffer::free);
      return ref;
   }

   public ThreadLocalMemoryBuffer(long size) {
      this.threadLocal = ThreadLocal.withInitial(() -> createMemoryBuffer(size));
   }

   public static MemoryBuffer create(long size) {
      return createMemoryBuffer(size);
   }

   public MemoryBuffer get() {
      return this.threadLocal.get();
   }
}
