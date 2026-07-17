package me.cortex.voxy.common.config.storage;

import java.util.ArrayList;
import java.util.List;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.Serialization;

public abstract class StorageConfig {
   public abstract StorageBackend build(ConfigBuildCtx var1);

   public List<StorageConfig> getChildStorageConfigs() {
      return List.of();
   }

   public final List<StorageConfig> collectStorageConfigs() {
      List<StorageConfig> configs = new ArrayList<>();
      configs.add(this);

      for (StorageConfig child : this.getChildStorageConfigs()) {
         configs.addAll(child.collectStorageConfigs());
      }

      return configs;
   }

   static {
      Serialization.CONFIG_TYPES.add(StorageConfig.class);
   }
}
