package me.cortex.voxy.common.config.compressors;

import me.cortex.voxy.common.util.MemoryBuffer;

public interface StorageCompressor {
   MemoryBuffer compress(MemoryBuffer var1);

   MemoryBuffer decompress(MemoryBuffer var1);

   void close();
}
