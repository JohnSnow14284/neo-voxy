package me.cortex.voxy.commonImpl;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class WorldIdentifier {
   private static final ResourceKey<DimensionType> NULL_DIM_KEY = ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse("voxy:null_dimension_id"));
   public final ResourceKey<Level> key;
   public final long biomeSeed;
   public final ResourceKey<DimensionType> dimension;
   private final transient long hashCode;
   @Nullable
   transient WeakReference<WorldEngine> cachedEngineObject;

   public WorldIdentifier(@NotNull ResourceKey<Level> key, long biomeSeed, @Nullable ResourceKey<DimensionType> dimension) {
      if (key == null) {
         throw new IllegalStateException("Key cannot be null");
      } else {
         dimension = dimension == null ? NULL_DIM_KEY : dimension;
         this.key = key;
         this.biomeSeed = biomeSeed;
         this.dimension = dimension;
         this.hashCode = mixStafford13(registryKeyHashCode(key)) ^ mixStafford13(registryKeyHashCode(dimension)) ^ mixStafford13(biomeSeed);
      }
   }

   @Override
   public int hashCode() {
      return (int)this.hashCode;
   }

   @Override
   public boolean equals(Object obj) {
      return !(obj instanceof WorldIdentifier other)
         ? false
         : other.hashCode == this.hashCode && other.biomeSeed == this.biomeSeed && equal(other.key, this.key) && equal(other.dimension, this.dimension);
   }

   private static <T> boolean equal(ResourceKey<T> a, ResourceKey<T> b) {
      if (a == b) {
         return true;
      } else {
         return a != null && b != null ? a.registry().equals(b.registry()) && a.identifier().equals(b.identifier()) : false;
      }
   }

   public WorldEngine getOrCreateEngine() {
      return this.getOrCreateEngine(false);
   }

   public WorldEngine getOrCreateEngine(boolean allowNull) {
      VoxyInstance instance = VoxyCommon.getInstance();
      if (instance == null) {
         this.cachedEngineObject = null;
         return null;
      } else {
         WorldEngine engine = instance.getOrCreate(this);
         if (allowNull && engine == null) {
            throw new IllegalStateException("Engine null on creation");
         } else {
            return engine;
         }
      }
   }

   public WorldEngine getNullable() {
      VoxyInstance instance = VoxyCommon.getInstance();
      if (instance == null) {
         this.cachedEngineObject = null;
         return null;
      } else {
         return instance.getNullable(this);
      }
   }

   public static WorldIdentifier of(Level level) {
      return level == null ? null : ((IWorldGetIdentifier)level).voxy$getIdentifier();
   }

   public static WorldEngine ofEngine(Level level) {
      WorldIdentifier id = of(level);
      return id == null ? null : id.getOrCreateEngine();
   }

   public static WorldEngine ofEngineNullable(Level level) {
      WorldIdentifier id = of(level);
      return id == null ? null : id.getNullable();
   }

   public static long mixStafford13(long seed) {
      seed += 918759875987111L;
      seed = (seed ^ seed >>> 30) * -4658895280553007687L;
      seed = (seed ^ seed >>> 27) * -7723592293110705685L;
      return seed ^ seed >>> 31;
   }

   public long getLongHash() {
      return this.hashCode;
   }

   private static long registryKeyHashCode(ResourceKey<?> key) {
      Identifier A = key.registry();
      Identifier B = key.identifier();
      int a = A == null ? 0 : A.hashCode();
      int b = B == null ? 0 : B.hashCode();
      return Integer.toUnsignedLong(a) << 32 | Integer.toUnsignedLong(b);
   }

   private static String bytesToHex(byte[] hash) {
      StringBuilder hexString = new StringBuilder(2 * hash.length);

      for (byte b : hash) {
         String hex = Integer.toHexString(255 & b);
         if (hex.length() == 1) {
            hexString.append('0');
         }

         hexString.append(hex);
      }

      return hexString.toString();
   }

   public String getWorldId() {
      return getWorldId(this);
   }

   public static String getWorldId(WorldIdentifier identifier) {
      String data = identifier.biomeSeed + identifier.key.toString();

      try {
         return bytesToHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes())).substring(0, 32);
      } catch (NoSuchAlgorithmException var3) {
         throw new RuntimeException(var3);
      }
   }

   @Override
   public String toString() {
      return "WorldIdentifier[" + this.key.identifier().toString() + ", " + this.biomeSeed + ", " + this.dimension.identifier().toString() + "]";
   }

   public static class GsonAdapter extends TypeAdapter<WorldIdentifier> {
      public static final WorldIdentifier.GsonAdapter INSTANCE = new WorldIdentifier.GsonAdapter();
      private static final Gson GSON = new Gson();

      private GsonAdapter() {
      }

      public void write(JsonWriter writer, WorldIdentifier identifier) throws IOException {
         writer.beginObject();
         writer.name("key");
         writer.value(identifier.key.identifier().toString());
         writer.name("biomeSeed");
         writer.value(identifier.biomeSeed);
         writer.name("dimension");
         writer.value(identifier.dimension.identifier().toString());
         writer.endObject();
      }

      public WorldIdentifier read(JsonReader reader) throws IOException {
         JsonObject obj = ((JsonElement)GSON.getAdapter(JsonElement.class).read(reader)).getAsJsonObject();
         String sKey = obj.getAsJsonPrimitive("key").getAsString();
         long biomeSeed = obj.getAsJsonPrimitive("biomeSeed").getAsLong();
         String sDim = obj.getAsJsonPrimitive("dimension").getAsString();
         ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(sKey));
         ResourceKey<DimensionType> dim = ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.parse(sDim));
         return new WorldIdentifier(key, biomeSeed, dim);
      }
   }
}
