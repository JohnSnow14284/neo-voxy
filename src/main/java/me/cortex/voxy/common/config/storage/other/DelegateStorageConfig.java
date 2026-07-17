package me.cortex.voxy.common.config.storage.other;

import java.util.List;
import me.cortex.voxy.common.config.storage.StorageConfig;

public abstract class DelegateStorageConfig extends StorageConfig {
   public StorageConfig delegate;

   @Override
   public List<StorageConfig> getChildStorageConfigs() {
      return List.of(this.delegate);
   }
}
