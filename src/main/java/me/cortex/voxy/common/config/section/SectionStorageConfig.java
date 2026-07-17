package me.cortex.voxy.common.config.section;

import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.Serialization;

public abstract class SectionStorageConfig {
   public abstract SectionStorage build(ConfigBuildCtx var1);

   static {
      Serialization.CONFIG_TYPES.add(SectionStorageConfig.class);
   }
}
