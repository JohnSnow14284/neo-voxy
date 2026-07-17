package me.cortex.voxy.common.config.compressors;

import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.util.GlobalCleaner;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ResizingThreadLocalMemoryBuffer;
import org.lwjgl.util.zstd.Zstd;

public class ZSTDCompressor implements StorageCompressor {
   private static final ThreadLocal<ZSTDCompressor.Ref> COMPRESSION_CTX = ThreadLocal.withInitial(ZSTDCompressor::createCleanableCompressionContext);
   private static final ThreadLocal<ZSTDCompressor.Ref> DECOMPRESSION_CTX = ThreadLocal.withInitial(ZSTDCompressor::createCleanableDecompressionContext);
   private static final ResizingThreadLocalMemoryBuffer SCRATCH = new ResizingThreadLocalMemoryBuffer(525320L);
   private final int level;

   private static ZSTDCompressor.Ref createCleanableCompressionContext() {
      long ctx = Zstd.ZSTD_createCCtx();
      ZSTDCompressor.Ref ref = new ZSTDCompressor.Ref(ctx);
      GlobalCleaner.CLEANER.register(ref, () -> Zstd.ZSTD_freeCCtx(ctx));
      return ref;
   }

   private static ZSTDCompressor.Ref createCleanableDecompressionContext() {
      long ctx = Zstd.ZSTD_createDCtx();
      Zstd.nZSTD_DCtx_setParameter(ctx, 1002, 1);
      ZSTDCompressor.Ref ref = new ZSTDCompressor.Ref(ctx);
      GlobalCleaner.CLEANER.register(ref, () -> Zstd.ZSTD_freeDCtx(ctx));
      return ref;
   }

   public ZSTDCompressor(int level) {
      this.level = level;
   }

   @Override
   public MemoryBuffer compress(MemoryBuffer saveData) {
      MemoryBuffer compressedData = SCRATCH.get(Zstd.ZSTD_COMPRESSBOUND(saveData.size)).createUntrackedUnfreeableReference();
      long compressedSize = Zstd.nZSTD_compressCCtx(
         COMPRESSION_CTX.get().ptr, compressedData.address, compressedData.size, saveData.address, saveData.size, this.level
      );
      return compressedData.subSize(compressedSize);
   }

   @Override
   public MemoryBuffer decompress(MemoryBuffer saveData) {
      MemoryBuffer decompressed = SCRATCH.get().createUntrackedUnfreeableReference();
      long size = Zstd.nZSTD_decompressDCtx(DECOMPRESSION_CTX.get().ptr, decompressed.address, decompressed.size, saveData.address, saveData.size);
      return decompressed.subSize(size);
   }

   @Override
   public void close() {
   }

   public static class Config extends CompressorConfig {
      public int compressionLevel;

      @Override
      public StorageCompressor build(ConfigBuildCtx ctx) {
         return new ZSTDCompressor(this.compressionLevel);
      }

      public static String getConfigTypeName() {
         return "ZSTD";
      }
   }

   private record Ref(long ptr) {
   }
}
