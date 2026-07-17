package me.cortex.voxy.common.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;
import me.cortex.voxy.common.Logger;

public class Serialization {
   public static final Set<Class<?>> CONFIG_TYPES = new HashSet<>();
   public static Gson GSON;

   public static void init() {
      Map<Class<?>, Serialization.GsonConfigSerialization<?>> serializers = new HashMap<>();
      String[][] configClassNames = new String[][]{
         {"me.cortex.voxy.common.config.compressors.LZ4Compressor$Config"},
         {"me.cortex.voxy.common.config.compressors.ZSTDCompressor$Config"},
         {"me.cortex.voxy.common.config.storage.lmdb.LMDBStorageBackend$Config"},
         {"me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend$Config"},
         {"me.cortex.voxy.common.config.storage.redis.RedisStorageBackend$Config", "redis.clients.jedis.JedisPool"},
         {"me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend$Config", "org.rocksdb.RocksDB"},
         {"me.cortex.voxy.common.config.storage.other.ReadonlyCachingLayer$Config"},
         {"me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor$Config"},
         {"me.cortex.voxy.common.config.storage.other.ConditionalStorageBackendConfig"},
         {"me.cortex.voxy.common.config.storage.other.FragmentedStorageBackendAdaptor$Config"},
         {"me.cortex.voxy.common.config.storage.other.FragmentedStorageBackendAdaptor$Config2"},
         {"me.cortex.voxy.common.config.storage.other.BasicPathInsertionConfig"},
         {"me.cortex.voxy.common.config.section.SectionSerializationStorage$Config"}
      };
      int count = 0;

      for (String[] registration : configClassNames) {
         String className = registration[0];

         try {
            if (registration.length > 1) {
               try {
                  Class.forName(registration[1], false, Serialization.class.getClassLoader());
               } catch (NoClassDefFoundError | ClassNotFoundException var13) {
                  continue;
               }
            }

            Class<?> original = Class.forName(className, true, Serialization.class.getClassLoader());
            if (!Modifier.isAbstract(original.getModifiers())) {
               Class<?> clz = original;

               while ((clz = clz.getSuperclass()) != null) {
                  if (CONFIG_TYPES.contains(clz)) {
                     Method nameMethod = null;

                     try {
                        nameMethod = original.getMethod("getConfigTypeName");
                        nameMethod.setAccessible(true);
                     } catch (NoSuchMethodException var12) {
                     }

                     if (nameMethod == null) {
                        Logger.error("WARNING: Config class " + className + " doesnt contain a getConfigTypeName and thus wont be serializable");
                     } else {
                        count++;
                        String name = (String)nameMethod.invoke(null);
                        registerConfigType(serializers, clz, name, original);
                        Logger.info("Registered " + original.getSimpleName() + " as " + name + " for config type " + clz.getSimpleName());
                     }
                     break;
                  }
               }
            }
         } catch (ClassNotFoundException var14) {
            Logger.error("Config class not found: " + className, var14);
         } catch (Exception var15) {
            Logger.error("Error registering config class: " + className, var15);
         }
      }

      GsonBuilder builder = new GsonBuilder().setPrettyPrinting();

      for (Entry<Class<?>, Serialization.GsonConfigSerialization<?>> entry : serializers.entrySet()) {
         builder.registerTypeAdapterFactory(entry.getValue());
      }

      GSON = builder.create();
      Logger.info("Registered " + count + " config types");
   }

   @SuppressWarnings({"rawtypes", "unchecked"})
   private static void registerConfigType(Map<Class<?>, Serialization.GsonConfigSerialization<?>> serializers,
                                          Class<?> baseType, String name, Class<?> implementation) {
      Serialization.GsonConfigSerialization serializer = serializers.computeIfAbsent(
         baseType, type -> new Serialization.GsonConfigSerialization(type)
      );
      serializer.register(name, implementation);
   }

   private static final class GsonConfigSerialization<T> implements TypeAdapterFactory {
      private final String typeField = "TYPE";
      private final Class<T> clz;
      private final Map<String, Class<? extends T>> name2type = new HashMap<>();
      private final Map<Class<? extends T>, String> type2name = new HashMap<>();

      private GsonConfigSerialization(Class<T> clz) {
         this.clz = clz;
      }

      public Serialization.GsonConfigSerialization<T> register(String typeName, Class<? extends T> cls) {
         if (this.name2type.put(typeName, cls) != null) {
            throw new IllegalStateException("Type name already registered: " + typeName);
         } else if (this.type2name.put(cls, typeName) != null) {
            throw new IllegalStateException("Class already registered with type name: " + typeName + ", " + cls);
         } else {
            return this;
         }
      }

      private T deserialize(Gson gson, JsonElement json) {
         Class<? extends T> retype = this.name2type.get(json.getAsJsonObject().remove("TYPE").getAsString());
         return (T)gson.getDelegateAdapter(this, TypeToken.get(retype)).fromJsonTree(json);
      }

      private JsonElement serialize(Gson gson, T value) {
         String name = this.type2name.get(value.getClass());
         if (name == null) {
            name = "UNKNOWN_TYPE_{" + value.getClass().getName() + "}";
         }

         @SuppressWarnings("unchecked")
         TypeAdapter<T> delegate = (TypeAdapter<T>)(TypeAdapter<?>)gson.getDelegateAdapter(this, TypeToken.get(value.getClass()));
         JsonElement vjson = delegate.toJsonTree(value);
         JsonObject json = new JsonObject();
         json.addProperty("TYPE", name);
         vjson.getAsJsonObject().asMap().forEach(json::add);
         return json;
      }

      public <X> TypeAdapter<X> create(final Gson gson, TypeToken<X> type) {
         if (this.clz.isAssignableFrom(type.getRawType())) {
            final TypeAdapter<JsonElement> jsonObjectAdapter = gson.getAdapter(JsonElement.class);
            return (TypeAdapter<X>)(new TypeAdapter<T>() {
               {
                  Objects.requireNonNull(GsonConfigSerialization.this);
               }

               public void write(JsonWriter out, T value) throws IOException {
                  jsonObjectAdapter.write(out, GsonConfigSerialization.this.serialize(gson, value));
               }

               public T read(JsonReader in) throws IOException {
                  JsonElement obj = (JsonElement)jsonObjectAdapter.read(in);
                  return (T)GsonConfigSerialization.this.deserialize(gson, obj);
               }
            });
         } else {
            return null;
         }
      }
   }
}
