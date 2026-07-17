package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.fml.loading.FMLPaths;

public class VoxyConfig {
   private static final Gson GSON = new GsonBuilder()
      .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
      .setPrettyPrinting()
      .excludeFieldsWithModifiers(new int[]{2})
      .create();
   public static VoxyConfig CONFIG = loadOrCreate();
   public boolean enabled = true;
   public boolean enableRendering = true;
   public boolean ingestEnabled = true;
   public float sectionRenderDistance = 16.0F;
   public int serviceThreads = (int)Math.max(CpuLayout.getCoreCount() / 1.5, 1.0);
   public float subDivisionSize = 64.0F;
   public boolean useEnvironmentalFog = true;
   public boolean dontUseSodiumBuilderThreads = false;
   public String ssaoMode;

   public SSAO.SSAOMode getSSAOMode() {
      if (this.ssaoMode == null) {
         return SSAO.SSAOMode.AUTO;
      } else {
         try {
            return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
         } catch (Exception var2) {
            return SSAO.SSAOMode.AUTO;
         }
      }
   }

   public void setSSAOMode(SSAO.SSAOMode mode) {
      this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
   }

   private static VoxyConfig loadOrCreate() {
      if (!VoxyCommon.isAvailable()) {
         VoxyConfig config = new VoxyConfig();
         config.enabled = false;
         config.enableRendering = false;
         return config;
      } else {
         Path path = getConfigPath();
         if (Files.exists(path)) {
            try {
               label47: {
                  VoxyConfig var3;
                  try (FileReader reader = new FileReader(path.toFile())) {
                     VoxyConfig conf = (VoxyConfig)GSON.fromJson(reader, VoxyConfig.class);
                     if (conf == null) {
                        Logger.error("Failed to load voxy config, resetting");
                        break label47;
                     }

                     conf.save();
                     var3 = conf;
                  }

                  return var3;
               }
            } catch (IOException var6) {
               Logger.error("Could not load config", var6);
            } catch (JsonParseException var7) {
               Logger.error("Could not parse config", var7);
            }

            Logger.info("Error during config loading, creating new");
         } else {
            Logger.info("Config file doesnt exist, creating new");
         }

         VoxyConfig config = new VoxyConfig();
         config.save();
         return config;
      }
   }

   public void save() {
      if (!VoxyCommon.isAvailable()) {
         Logger.info("Not saving config since voxy is unavalible");
      } else {
         try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
         } catch (IOException var2) {
            Logger.error("Failed to write config file", var2);
         }
      }
   }

   private static Path getConfigPath() {
      return FMLPaths.CONFIGDIR.get().resolve("voxy-config.json");
   }

   public boolean isRenderingEnabled() {
      return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
   }
}
