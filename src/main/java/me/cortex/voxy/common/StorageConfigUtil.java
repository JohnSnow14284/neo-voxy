package me.cortex.voxy.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.function.Supplier;
import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.config.compressors.ZSTDCompressor;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;

public class StorageConfigUtil {
   public static <T> T getCreateStorageConfig(Class<T> clz, Predicate<T> verifier, Supplier<T> defaultConfig, Path path) {
      try {
         Files.createDirectories(path);
      } catch (Exception var9) {
         throw new RuntimeException(var9);
      }

      Path json = path.resolve("config.json");
      T config = null;
      if (Files.exists(json)) {
         try {
            config = (T)Serialization.GSON.fromJson(Files.readString(json), clz);
            if (config == null) {
               Logger.error("Config deserialization null, reverting to default");
            } else if (!verifier.test(config)) {
               Logger.error("Config section storage null, reverting to default");
               config = null;
            }
         } catch (Exception var8) {
            Logger.error(
               "Failed to load the storage configuration file, resetting it to default, this will probably break your save if you used a custom storage config",
               var8
            );
         }
      }

      if (config == null) {
         config = defaultConfig.get();
      }

      try {
         Files.writeString(json, Serialization.GSON.toJson(config));
      } catch (Exception var7) {
         throw new RuntimeException("Failed write the config, aborting!", var7);
      }

      if (config == null) {
         throw new IllegalStateException("Config is still null\n");
      } else {
         return config;
      }
   }

   public static SectionSerializationStorage.Config createDefaultSerializer() {
      RocksDBStorageBackend.Config baseDB = new RocksDBStorageBackend.Config();
      ZSTDCompressor.Config compressor = new ZSTDCompressor.Config();
      compressor.compressionLevel = 1;
      CompressionStorageAdaptor.Config compression = new CompressionStorageAdaptor.Config();
      compression.delegate = baseDB;
      compression.compressor = compressor;
      SectionSerializationStorage.Config serializer = new SectionSerializationStorage.Config();
      serializer.storage = compression;
      return serializer;
   }
}
