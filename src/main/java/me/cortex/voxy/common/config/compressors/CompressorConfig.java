package me.cortex.voxy.common.config.compressors;

import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.Serialization;

public abstract class CompressorConfig {
   public abstract StorageCompressor build(ConfigBuildCtx var1);

   static {
      Serialization.CONFIG_TYPES.add(CompressorConfig.class);
   }
}
