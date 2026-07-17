package me.cortex.voxy.common.config;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.ByteBuffer;

public interface IMappingStorage {
   void putIdMapping(int var1, ByteBuffer var2);

   Int2ObjectOpenHashMap<byte[]> getIdMappingsData();

   void flush();

   void close();
}
