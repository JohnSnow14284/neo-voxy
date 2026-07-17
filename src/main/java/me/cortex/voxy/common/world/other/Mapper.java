package me.cortex.voxy.common.world.other;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DataResult.Error;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.util.Pair;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

public class Mapper {
   private static final int BLOCK_STATE_TYPE = 1;
   private static final int BIOME_TYPE = 2;
   private final IMappingStorage storage;
   public static final long UNKNOWN_MAPPING = -1L;
   public static final long AIR = 0L;
   private final ReentrantLock blockLock = new ReentrantLock();
   private final ConcurrentHashMap<BlockState, Mapper.StateEntry> block2stateEntry = new ConcurrentHashMap<>(2000, 0.75F, 10);
   private final ObjectArrayList<Mapper.StateEntry> blockId2stateEntry = new ObjectArrayList();
   private final ReentrantLock biomeLock = new ReentrantLock();
   private final ConcurrentHashMap<String, Mapper.BiomeEntry> biome2biomeEntry = new ConcurrentHashMap<>(2000, 0.75F, 10);
   private final ObjectArrayList<Mapper.BiomeEntry> biomeId2biomeEntry = new ObjectArrayList();
   private Consumer<Mapper.StateEntry> newStateCallback;
   private Consumer<Mapper.BiomeEntry> newBiomeCallback;

   public Mapper(IMappingStorage storage) {
      this.storage = storage;
      Mapper.StateEntry airEntry = new Mapper.StateEntry(0, Blocks.AIR.defaultBlockState());
      this.block2stateEntry.put(airEntry.state, airEntry);
      this.blockId2stateEntry.add(airEntry);
      this.loadFromStorage();
   }

   public static boolean isAir(long id) {
      return (id & 140737354137600L) == 0L;
   }

   public static int getBlockId(long id) {
      return (int)(id >> 27 & 1048575L);
   }

   public static int getBiomeId(long id) {
      return (int)(id >> 47 & 511L);
   }

   public static int getLightId(long id) {
      return (int)(id >> 56 & 255L);
   }

   public static long withLight(long id, int light) {
      return id & 72057594037927935L | Integer.toUnsignedLong(light & 0xFF) << 56;
   }

   public static long withBlockBiome(long id, int block, int biome) {
      return id & -72057594037927936L | Integer.toUnsignedLong(block) << 27 | Integer.toUnsignedLong(biome) << 47;
   }

   public static long airWithLight(int light) {
      return Integer.toUnsignedLong(light & 0xFF) << 56;
   }

   public void setStateCallback(Consumer<Mapper.StateEntry> stateCallback) {
      this.newStateCallback = stateCallback;
   }

   public void setBiomeCallback(Consumer<Mapper.BiomeEntry> biomeCallback) {
      this.newBiomeCallback = biomeCallback;
   }

   private void loadFromStorage() {
      Int2ObjectOpenHashMap<byte[]> mappings = this.storage.getIdMappingsData();
      List<Mapper.StateEntry> sentries = new ArrayList<>();
      List<Mapper.BiomeEntry> bentries = new ArrayList<>();
      List<Pair<byte[], Integer>> sentryErrors = new ArrayList<>();
      boolean[] forceResave = new boolean[1];
      ObjectIterator rand = mappings.int2ObjectEntrySet().iterator();

      while (rand.hasNext()) {
         Entry<byte[]> entry = (Entry<byte[]>)rand.next();
         int entryType = entry.getIntKey() >>> 30;
         int id = entry.getIntKey() & 1073741823;
         if (entryType == 1) {
            Mapper.StateEntry sentry = Mapper.StateEntry.deserialize(id, (byte[])entry.getValue(), forceResave);
            if (sentry.state.isAir()) {
               Logger.error("Deserialization was air, removed block");
               sentryErrors.add(new Pair<>((byte[])entry.getValue(), id));
            } else {
               sentries.add(sentry);
               Mapper.StateEntry oldEntry = this.block2stateEntry.putIfAbsent(sentry.state, sentry);
               if (oldEntry != null) {
                  Logger.warn(
                     "Multiple mappings for blockstate, using old state, expect things to possibly go really badly. "
                        + oldEntry.id
                        + ":"
                        + sentry.id
                        + ":"
                        + sentry.state
                  );
               }
            }
         } else {
            if (entryType != 2) {
               throw new IllegalStateException("Unknown entryType");
            }

            Mapper.BiomeEntry bentry = Mapper.BiomeEntry.deserialize(id, (byte[])entry.getValue());
            bentries.add(bentry);
            if (this.biome2biomeEntry.put(bentry.biome, bentry) != null) {
               throw new IllegalStateException("Multiple mappings for biome entry");
            }
         }
      }

      if (!sentryErrors.isEmpty()) {
         forceResave[0] |= true;
         Random randx = new Random();

         for (Pair<byte[], Integer> error : sentryErrors) {
            Mapper.StateEntry state;
            do {
               state = new Mapper.StateEntry(error.right(), (BlockState)Block.BLOCK_STATE_REGISTRY.byId(randx.nextInt(Block.BLOCK_STATE_REGISTRY.size() - 1)));
            } while (this.block2stateEntry.put(state.state, state) != null);

            sentries.add(state);
         }
      }

      sentries.stream().sorted(Comparator.comparing(a -> a.id)).forEach(entryx -> {
         if (this.blockId2stateEntry.size() != entryx.id) {
            throw new IllegalStateException("Block entry not ordered");
         } else {
            this.blockId2stateEntry.add(entryx);
         }
      });
      bentries.stream()
         .sorted(Comparator.comparing(a -> a.id))
         .forEach(
            entryx -> {
               if (this.biomeId2biomeEntry.size() != entryx.id) {
                  throw new IllegalStateException(
                     "Biome entry not ordered. got " + entryx.biome + " with id " + entryx.id + " expected id " + this.biomeId2biomeEntry.size()
                  );
               } else {
                  this.biomeId2biomeEntry.add(entryx);
               }
            }
         );
      if (forceResave[0]) {
         Logger.warn("Forced state resave triggered");
         this.forceResaveStates();
      }
   }

   public final int getBlockStateCount() {
      return this.blockId2stateEntry.size();
   }

   private Mapper.StateEntry registerNewBlockState(BlockState state) {
      this.blockLock.lock();
      Mapper.StateEntry entry = this.block2stateEntry.get(state);
      if (entry != null) {
         this.blockLock.unlock();
         return entry;
      } else {
         entry = new Mapper.StateEntry(this.blockId2stateEntry.size(), state);
         this.blockId2stateEntry.add(entry);
         this.block2stateEntry.put(state, entry);
         this.blockLock.unlock();
         byte[] serialized = entry.serialize();
         ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
         buffer.put(serialized);
         buffer.rewind();
         this.storage.putIdMapping(entry.id | 1073741824, buffer);
         MemoryUtil.memFree(buffer);
         if (this.newStateCallback != null) {
            this.newStateCallback.accept(entry);
         }

         return entry;
      }
   }

   private Mapper.BiomeEntry registerNewBiome(String biome) {
      this.biomeLock.lock();
      Mapper.BiomeEntry entry = this.biome2biomeEntry.get(biome);
      if (entry != null) {
         this.biomeLock.unlock();
         return entry;
      } else {
         entry = new Mapper.BiomeEntry(this.biomeId2biomeEntry.size(), biome);
         this.biomeId2biomeEntry.add(entry);
         this.biome2biomeEntry.put(biome, entry);
         this.biomeLock.unlock();
         byte[] serialized = entry.serialize();
         ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
         buffer.put(serialized);
         buffer.rewind();
         this.storage.putIdMapping(entry.id | -2147483648, buffer);
         MemoryUtil.memFree(buffer);
         if (this.newBiomeCallback != null) {
            this.newBiomeCallback.accept(entry);
         }

         return entry;
      }
   }

   public long getBaseId(byte light, BlockState state, Holder<Biome> biome) {
      return state.isAir() ? Byte.toUnsignedLong(light) << 56 : composeMappingId(light, this.getIdForBlockState(state), this.getIdForBiome(biome));
   }

   public BlockState getBlockStateFromBlockId(int blockId) {
      return ((Mapper.StateEntry)this.blockId2stateEntry.get(blockId)).state;
   }

   public int getIdForBlockState(BlockState state) {
      if (state.isAir()) {
         return 0;
      } else {
         Mapper.StateEntry mapping = this.block2stateEntry.get(state);
         if (mapping == null) {
            mapping = this.registerNewBlockState(state);
         }

         return mapping.id;
      }
   }

   public int getBlockStateOpacity(long mappingId) {
      return this.getBlockStateOpacity(getBlockId(mappingId));
   }

   public int getBlockStateOpacity(int blockId) {
      return ((Mapper.StateEntry)this.blockId2stateEntry.get(blockId)).opacity;
   }

   public int getIdForBiome(Holder<Biome> biome) {
      String biomeId = ((ResourceKey)biome.unwrapKey().get()).identifier().toString();
      Mapper.BiomeEntry entry = this.biome2biomeEntry.get(biomeId);
      if (entry == null) {
         entry = this.registerNewBiome(biomeId);
      }

      return entry.id;
   }

   public static long composeMappingId(byte light, int blockId, int biomeId) {
      return blockId == 0L
         ? Byte.toUnsignedLong(light) << 56
         : Byte.toUnsignedLong(light) << 56 | Integer.toUnsignedLong(biomeId) << 47 | Integer.toUnsignedLong(blockId) << 27;
   }

   public Mapper.StateEntry[] getStateEntries() {
      this.blockLock.lock();
      ArrayList<Mapper.StateEntry> set = new ArrayList<>(this.blockId2stateEntry);
      Mapper.StateEntry[] out = new Mapper.StateEntry[set.size()];
      int i = 0;

      for (Mapper.StateEntry entry : set) {
         if (entry.id != i++) {
            throw new IllegalStateException();
         }

         out[i - 1] = entry;
      }

      this.blockLock.unlock();
      return out;
   }

   public Mapper.BiomeEntry[] getBiomeEntries() {
      this.biomeLock.lock();
      ArrayList<Mapper.BiomeEntry> set = new ArrayList<>(this.biomeId2biomeEntry);
      Mapper.BiomeEntry[] out = new Mapper.BiomeEntry[set.size()];
      int i = 0;

      for (Mapper.BiomeEntry entry : set) {
         if (entry.id != i++) {
            throw new IllegalStateException();
         }

         out[i - 1] = entry;
      }

      this.biomeLock.unlock();
      return out;
   }

   public void forceResaveStates() {
      ArrayList<Mapper.StateEntry> blocks = new ArrayList<>(this.block2stateEntry.values());
      ArrayList<Mapper.BiomeEntry> biomes = new ArrayList<>(this.biome2biomeEntry.values());

      for (Mapper.StateEntry entry : blocks) {
         if (!entry.state.isAir() || entry.id != 0) {
            if (this.blockId2stateEntry.indexOf(entry) != entry.id) {
               throw new IllegalStateException(
                  "State Id NOT THE SAME, very critically bad. arr:" + this.blockId2stateEntry.indexOf(entry) + " entry: " + entry.id
               );
            }

            byte[] serialized = entry.serialize();
            ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
            buffer.put(serialized);
            buffer.rewind();
            this.storage.putIdMapping(entry.id | 1073741824, buffer);
            MemoryUtil.memFree(buffer);
         }
      }

      for (Mapper.BiomeEntry entryx : biomes) {
         if (this.biomeId2biomeEntry.indexOf(entryx) != entryx.id) {
            throw new IllegalStateException("Biome Id NOT THE SAME, very critically bad");
         }

         byte[] serialized = entryx.serialize();
         ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
         buffer.put(serialized);
         buffer.rewind();
         this.storage.putIdMapping(entryx.id | -2147483648, buffer);
         MemoryUtil.memFree(buffer);
      }

      this.storage.flush();
   }

   public void close() {
   }

   public static final class BiomeEntry {
      public final int id;
      public final String biome;

      public BiomeEntry(int id, String biome) {
         this.id = id;
         this.biome = biome;
      }

      public byte[] serialize() {
         try {
            CompoundTag serialized = new CompoundTag();
            serialized.putInt("id", this.id);
            serialized.putString("biome_id", this.biome);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(serialized, out);
            return out.toByteArray();
         } catch (Exception var3) {
            throw new RuntimeException(var3);
         }
      }

      public static Mapper.BiomeEntry deserialize(int id, byte[] data) {
         try {
            CompoundTag compound = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
            if (compound.getIntOr("id", -1) != id) {
               throw new IllegalStateException("Encoded id != expected id");
            } else {
               String biome = compound.getStringOr("biome_id", null);
               return new Mapper.BiomeEntry(id, biome);
            }
         } catch (IOException var4) {
            throw new RuntimeException(var4);
         }
      }
   }

   public static final class StateEntry {
      public final int id;
      public final BlockState state;
      public final int opacity;

      public StateEntry(int id, BlockState state) {
         this.id = id;
         this.state = state;
         if (state.getBlock() instanceof LeavesBlock) {
            this.opacity = 15;
         } else {
            this.opacity = state.getLightDampening();
         }
      }

      public byte[] serialize() {
         try {
            CompoundTag serialized = new CompoundTag();
            serialized.putInt("id", this.id);
            serialized.put("block_state", (Tag)BlockState.CODEC.encodeStart(NbtOps.INSTANCE, this.state).result().get());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(serialized, out);
            return out.toByteArray();
         } catch (Exception var3) {
            throw new RuntimeException(var3);
         }
      }

      public static Mapper.StateEntry deserialize(int id, byte[] data, boolean[] forceResave) {
         try {
            CompoundTag compound = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
            if (compound.getIntOr("id", -1) != id) {
               throw new IllegalStateException("Encoded id != expected id");
            } else {
               CompoundTag bsc = (CompoundTag)compound.getCompound("block_state").orElseThrow();
               DataResult<BlockState> state = BlockState.CODEC.parse(NbtOps.INSTANCE, bsc);
               if (state.isError()) {
                  Logger.info("Could not decode blockstate, attempting fixes, error: " + ((Error)state.error().get()).message());
                  bsc = (CompoundTag)DataFixers.getDataFixer()
                     .update(References.BLOCK_STATE, new Dynamic(NbtOps.INSTANCE, bsc), 0, SharedConstants.getCurrentVersion().dataVersion().version())
                     .getValue();
                  state = BlockState.CODEC.parse(NbtOps.INSTANCE, bsc);
                  if (state.isError()) {
                     Logger.error("Could not decode blockstate setting to air. id:" + id + " error: " + ((Error)state.error().get()).message());
                     return new Mapper.StateEntry(id, Blocks.AIR.defaultBlockState());
                  } else {
                     Logger.info("Fixed blockstate to: " + state.getOrThrow());
                     forceResave[0] |= true;
                     return new Mapper.StateEntry(id, (BlockState)state.getOrThrow());
                  }
               } else {
                  return new Mapper.StateEntry(id, (BlockState)state.getOrThrow());
               }
            }
         } catch (IOException var6) {
            throw new RuntimeException(var6);
         }
      }
   }
}
