package me.cortex.voxy.common.config.storage.redis;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.LongConsumer;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class RedisStorageBackend extends StorageBackend {
   private final JedisPool pool;
   private final String user;
   private final String password;
   private final byte[] WORLD;
   private final byte[] MAPPINGS;

   public RedisStorageBackend(String host, int port, String prefix) {
      this(host, port, prefix, null, null);
   }

   public RedisStorageBackend(String host, int port, String prefix, String user, String password) {
      this.pool = new JedisPool(host, port);
      this.user = user;
      this.password = password;
      this.WORLD = (prefix + "world_sections").getBytes(StandardCharsets.UTF_8);
      this.MAPPINGS = (prefix + "id_mappings").getBytes(StandardCharsets.UTF_8);
   }

   @Override
   public void iteratePositions(int level, LongConsumer consumer) {
      throw new IllegalStateException("Not yet implemented");
   }

   @Override
   public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
      Jedis jedis = this.pool.getResource();

      MemoryBuffer var9;
      label47: {
         try {
            if (this.user != null) {
               jedis.auth(this.user, this.password);
            }

            byte[] result = jedis.hget(this.WORLD, longToBytes(key));
            if (result == null) {
               var9 = null;
               break label47;
            }

            UnsafeUtil.memcpy(result, scratch.address);
            var9 = scratch.subSize(result.length);
         } catch (Throwable var8) {
            if (jedis != null) {
               try {
                  jedis.close();
               } catch (Throwable var7) {
                  var8.addSuppressed(var7);
               }
            }

            throw var8;
         }

         if (jedis != null) {
            jedis.close();
         }

         return var9;
      }

      if (jedis != null) {
         jedis.close();
      }

      return var9;
   }

   @Override
   public void setSectionData(long key, MemoryBuffer data) {
      Jedis jedis = this.pool.getResource();

      try {
         if (this.user != null) {
            jedis.auth(this.user, this.password);
         }

         byte[] buffer = new byte[(int)data.size];
         UnsafeUtil.memcpy(data.address, buffer);
         jedis.hset(this.WORLD, longToBytes(key), buffer);
      } catch (Throwable var8) {
         if (jedis != null) {
            try {
               jedis.close();
            } catch (Throwable var7) {
               var8.addSuppressed(var7);
            }
         }

         throw var8;
      }

      if (jedis != null) {
         jedis.close();
      }
   }

   @Override
   public void deleteSectionData(long key) {
      Jedis jedis = this.pool.getResource();

      try {
         if (this.user != null) {
            jedis.auth(this.user, this.password);
         }

         jedis.hdel(this.WORLD, new byte[][]{longToBytes(key)});
      } catch (Throwable var7) {
         if (jedis != null) {
            try {
               jedis.close();
            } catch (Throwable var6) {
               var7.addSuppressed(var6);
            }
         }

         throw var7;
      }

      if (jedis != null) {
         jedis.close();
      }
   }

   @Override
   public void putIdMapping(int id, ByteBuffer data) {
      Jedis jedis = this.pool.getResource();

      try {
         if (this.user != null) {
            jedis.auth(this.user, this.password);
         }

         byte[] buffer = new byte[data.remaining()];
         data.get(buffer);
         data.rewind();
         jedis.hset(this.MAPPINGS, intToBytes(id), buffer);
      } catch (Throwable var7) {
         if (jedis != null) {
            try {
               jedis.close();
            } catch (Throwable var6) {
               var7.addSuppressed(var6);
            }
         }

         throw var7;
      }

      if (jedis != null) {
         jedis.close();
      }
   }

   @Override
   public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
      Jedis jedis = this.pool.getResource();

      Int2ObjectOpenHashMap var9;
      label54: {
         try {
            if (this.user != null) {
               jedis.auth(this.user, this.password);
            }

            Map<byte[], byte[]> mappings = jedis.hgetAll(this.MAPPINGS);
            Int2ObjectOpenHashMap<byte[]> out = new Int2ObjectOpenHashMap();
            if (mappings == null) {
               var9 = out;
               break label54;
            }

            for (Entry<byte[], byte[]> entry : mappings.entrySet()) {
               out.put(bytesToInt(entry.getKey()), entry.getValue());
            }

            var9 = out;
         } catch (Throwable var7) {
            if (jedis != null) {
               try {
                  jedis.close();
               } catch (Throwable var6) {
                  var7.addSuppressed(var6);
               }
            }

            throw var7;
         }

         if (jedis != null) {
            jedis.close();
         }

         return var9;
      }

      if (jedis != null) {
         jedis.close();
      }

      return var9;
   }

   @Override
   public void flush() {
   }

   @Override
   public void close() {
      this.pool.close();
   }

   private static byte[] intToBytes(int i) {
      return new byte[]{(byte)(i >> 24), (byte)(i >> 16), (byte)(i >> 8), (byte)i};
   }

   private static int bytesToInt(byte[] i) {
      return Byte.toUnsignedInt(i[0]) << 24 | Byte.toUnsignedInt(i[1]) << 16 | Byte.toUnsignedInt(i[2]) << 8 | Byte.toUnsignedInt(i[3]);
   }

   private static byte[] longToBytes(long l) {
      byte[] result = new byte[8];

      for (int i = 7; i >= 0; i--) {
         result[i] = (byte)(l & 255L);
         l >>= 8;
      }

      return result;
   }

   private static long bytesToLong(byte[] b) {
      long result = 0L;

      for (int i = 0; i < 8; i++) {
         result <<= 8;
         result |= b[i] & 255;
      }

      return result;
   }

   public static class Config extends StorageConfig {
      public String host;
      public int port;
      public String prefix;

      @Override
      public StorageBackend build(ConfigBuildCtx ctx) {
         return new RedisStorageBackend(this.host, this.port, ctx.substituteString(this.prefix));
      }

      public static String getConfigTypeName() {
         return "Redis";
      }
   }
}
