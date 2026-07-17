package me.cortex.voxy.common.config.storage.other;

import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.compressors.CompressorConfig;
import me.cortex.voxy.common.config.compressors.StorageCompressor;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.util.MemoryBuffer;

public class CompressionStorageAdaptor extends DelegatingStorageAdaptor {
   private final StorageCompressor compressor;

   public CompressionStorageAdaptor(StorageCompressor compressor, StorageBackend delegate) {
      super(delegate);
      this.compressor = compressor;
   }

   @Override
   public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
      MemoryBuffer data = this.delegate.getSectionData(key, scratch);
      return data == null ? null : this.compressor.decompress(data);
   }

   @Override
   public void setSectionData(long key, MemoryBuffer data) {
      MemoryBuffer cdata = this.compressor.compress(data);
      this.delegate.setSectionData(key, cdata);
   }

   @Override
   public void close() {
      this.compressor.close();
      super.close();
   }

   public static class Config extends DelegateStorageConfig {
      public CompressorConfig compressor;

      @Override
      public StorageBackend build(ConfigBuildCtx ctx) {
         return new CompressionStorageAdaptor(this.compressor.build(ctx), this.delegate.build(ctx));
      }

      public static String getConfigTypeName() {
         return "CompressionAdaptor";
      }
   }
}
