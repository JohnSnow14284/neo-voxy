package me.cortex.voxy.common;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.function.Supplier;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;

public class WorldConfigStorage<T> {
   private static final int FORMAT_VERSION = 1;
   private final Path file;
   private final Class<T> configType;
   private final LinkedHashMap<WorldIdentifier, T> worldConfigs = new LinkedHashMap<>();
   private final Gson gson;

   public WorldConfigStorage(Path file, Class<T> configType) {
      this(file, configType, null);
   }

   public WorldConfigStorage(Path file, Class<T> configType, TypeAdapter<T> adapter) {
      this.file = file;
      this.configType = configType;
      GsonBuilder builder = new GsonBuilder()
         .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
         .setPrettyPrinting()
         .excludeFieldsWithModifiers(new int[]{2})
         .registerTypeAdapter(WorldIdentifier.class, WorldIdentifier.GsonAdapter.INSTANCE)
         .registerTypeAdapter(WorldConfigStorage.InnerHolder.class, new TypeAdapter<WorldConfigStorage.InnerHolder<T>>() {
            {
               Objects.requireNonNull(WorldConfigStorage.this);
            }

            public void write(JsonWriter writer, WorldConfigStorage.InnerHolder<T> obj) throws IOException {
               writer.beginObject();
               writer.name("version");
               writer.value(1L);
               writer.name("configs");
               writer.beginArray();

               for (Entry<WorldIdentifier, T> entry : obj.worldConfigs.entrySet()) {
                  writer.beginObject();
                  writer.name("worldId");
                  if (entry.getKey() != null) {
                     WorldIdentifier.GsonAdapter.INSTANCE.write(writer, entry.getKey());
                  } else {
                     writer.nullValue();
                  }

                  writer.name("config");
                  if (entry.getValue() != null) {
                     WorldConfigStorage.this.gson.getAdapter(WorldConfigStorage.this.configType).write(writer, entry.getValue());
                  } else {
                     writer.nullValue();
                  }

                  writer.endObject();
               }

               writer.endArray();
               writer.endObject();
            }

            public WorldConfigStorage.InnerHolder<T> read(JsonReader in) throws IOException {
               JsonObject cfg = ((JsonElement)WorldConfigStorage.this.gson.getAdapter(JsonElement.class).read(in)).getAsJsonObject();
               JsonElement ver = cfg.get("version");
               if (ver.isJsonNull()) {
                  Logger.error("Version null");
                  return null;
               } else if (ver.getAsInt() != 1) {
                  Logger.error("Trying to load config from non matching version, got: " + ver.getAsInt() + " expect 1");
                  return null;
               } else {
                  WorldConfigStorage.InnerHolder<T> holder = new WorldConfigStorage.InnerHolder<>();
                  JsonElement cfgs = cfg.get("configs");

                  for (JsonElement objE : cfgs.getAsJsonArray()) {
                     JsonObject obj = objE.getAsJsonObject();
                     WorldIdentifier key = null;
                     T val = null;
                     JsonElement id = obj.get("worldId");
                     if (!id.isJsonNull()) {
                        key = (WorldIdentifier)WorldIdentifier.GsonAdapter.INSTANCE.fromJsonTree(id);
                     }

                     JsonElement valTree = obj.get("config");
                     if (!valTree.isJsonNull()) {
                        val = (T)WorldConfigStorage.this.gson.getAdapter(WorldConfigStorage.this.configType).fromJsonTree(valTree);
                     }

                     if (holder.worldConfigs.containsValue(key)) {
                        Logger.error("World config contained duplicate worldId keys: " + key + " overriding config");
                     }

                     holder.worldConfigs.put(key, val);
                  }

                  return holder;
               }
            }
         });
      if (adapter != null) {
         builder.registerTypeAdapter(configType, adapter);
      }

      this.gson = builder.create();
      this.load();
   }

   private void load() {
      if (Files.exists(this.file)) {
         try (FileReader reader = new FileReader(this.file.toFile())) {
            WorldConfigStorage.InnerHolder conf = (WorldConfigStorage.InnerHolder)this.gson.fromJson(reader, WorldConfigStorage.InnerHolder.class);
            if (conf != null) {
               this.worldConfigs.clear();
               this.worldConfigs.putAll(conf.worldConfigs);
            } else {
               Logger.error("Failed to load instance specific config, config contents discarded");
            }
         } catch (IOException var6) {
            Logger.error("Could not parse config", var6);
         }
      }
   }

   public T getOrCreate(WorldIdentifier id, Supplier<T> provider) {
      if (this.worldConfigs.containsKey(id)) {
         return this.worldConfigs.get(id);
      } else {
         T val = provider.get();
         this.worldConfigs.put(id, val);
         this.save();
         return val;
      }
   }

   public T getNullable(WorldIdentifier id) {
      return this.worldConfigs.getOrDefault(id, null);
   }

   public void put(WorldIdentifier id, T obj) {
      this.worldConfigs.put(id, obj);
      this.save();
   }

   public void remove(WorldIdentifier id) {
      this.worldConfigs.remove(id);
      this.save();
   }

   public void save() {
      if (!VoxyCommon.isAvailable()) {
         Logger.info("Not saving config since voxy is unavalible");
      } else {
         try {
            WorldConfigStorage.InnerHolder<T> holder = new WorldConfigStorage.InnerHolder<>();
            holder.worldConfigs.putAll(this.worldConfigs);
            Files.writeString(this.file, this.gson.toJson(holder));
         } catch (IOException var2) {
            Logger.error("Failed to write config file", var2);
         }
      }
   }

   private static class InnerHolder<T> {
      private final LinkedHashMap<WorldIdentifier, T> worldConfigs = new LinkedHashMap<>();
   }
}
