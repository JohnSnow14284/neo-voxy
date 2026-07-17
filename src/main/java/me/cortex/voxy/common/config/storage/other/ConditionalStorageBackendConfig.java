package me.cortex.voxy.common.config.storage.other;

import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import org.apache.commons.lang3.NotImplementedException;

public class ConditionalStorageBackendConfig extends StorageConfig {
   @Override
   public StorageBackend build(ConfigBuildCtx ctx) {
      throw new NotImplementedException();
   }

   public static String getConfigTypeName() {
      return "ConditionalConfig";
   }
}
