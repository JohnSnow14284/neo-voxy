package me.cortex.voxy.common.voxelization;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.WeakHashMap;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.Holder;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.SingleValuePalette;

public class WorldConversionFactory {
   private static final ThreadLocal<WorldConversionFactory.Cache> THREAD_LOCAL = ThreadLocal.withInitial(WorldConversionFactory.Cache::new);

   private static int setupLocalPalette(Palette<BlockState> vp, Reference2IntOpenHashMap<BlockState> blockCache, Mapper mapper, int[] pc) {
      int c = vp.getSize();
      if (vp instanceof LinearPalette) {
         for (int i = 0; i < vp.getSize(); i++) {
            BlockState state = (BlockState)vp.valueFor(i);
            int blockId = -1;
            if (state != null) {
               blockId = blockCache.getOrDefault(state, -1);
               if (blockId == -1) {
                  blockId = mapper.getIdForBlockState(state);
                  blockCache.put(state, blockId);
               }
            }

            pc[i] = blockId;
         }
      } else if (vp instanceof HashMapPalette<BlockState> pal) {
         for (int i = 0; i < vp.getSize(); i++) {
            BlockState state = null;
            int blockId = -1;

            try {
               state = (BlockState)vp.valueFor(i);
            } catch (Exception var10) {
            }

            if (state != null) {
               blockId = blockCache.getOrDefault(state, -1);
               if (blockId == -1) {
                  blockId = mapper.getIdForBlockState(state);
                  blockCache.put(state, blockId);
               }
            }

            pc[i] = blockId;
         }
      } else {
         if (!(vp instanceof SingleValuePalette)) {
            throw new IllegalStateException("Unknown palette type: " + vp);
         }

         int blockId = -1;
         BlockState state = (BlockState)vp.valueFor(0);
         if (state != null) {
            blockId = blockCache.getOrDefault(state, -1);
            if (blockId == -1) {
               blockId = mapper.getIdForBlockState(state);
               blockCache.put(state, blockId);
            }
         }

         pc[0] = blockId;
      }

      return c;
   }

   public static VoxelizedSection convert(
      VoxelizedSection section,
      Mapper stateMapper,
      PalettedContainer<BlockState> blockContainer,
      PalettedContainerRO<Holder<Biome>> biomeContainer,
      ILightingSupplier lightSupplier
   ) {
      return convert(section, stateMapper, blockContainer, biomeContainer, lightSupplier, false, 0L);
   }

   public static VoxelizedSection convert(
      VoxelizedSection section,
      Mapper stateMapper,
      PalettedContainer<BlockState> blockContainer,
      PalettedContainerRO<Holder<Biome>> biomeContainer,
      ILightingSupplier lightSupplier,
      boolean shouldZoom,
      long zoomSeed
   ) {
      WorldConversionFactory.Cache cache = THREAD_LOCAL.get();
      Reference2IntOpenHashMap<BlockState> blockCache = cache.getLocalMapping(stateMapper);
      int[] biomes = cache.biomeCache;
      long[] data = section.section;
      long[] zoomCells = cache.zoomCellCache;
      Palette<BlockState> vp = blockContainer.data.palette();
      int[] pc = cache.getPaletteCache(vp.getSize());
      GlobalPalette<BlockState> bps = null;
      int pcc = 0;
      if (blockContainer.data.palette() instanceof GlobalPalette<BlockState> _bps) {
         bps = _bps;
         pcc = _bps.getSize();
      } else {
         pcc = setupLocalPalette(vp, blockCache, stateMapper, pc);
         pcc = Math.max(0, pcc - 1);
      }

      int i = 0;
      int inital = -1;

      for (int y = 0; y < 4; y++) {
         for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 4; x++) {
               int bid = stateMapper.getIdForBiome((Holder<Biome>)biomeContainer.get(x, y, z));
               biomes[i++] = bid;
               if (inital == -1) {
                  inital = bid;
               }

               shouldZoom &= inital == bid;
            }
         }
      }

      if (shouldZoom) {
         computeZoomCells(biomes, zoomSeed, zoomCells);
      }

      i = 0;
      if (blockContainer.data.storage() instanceof SimpleBitStorage bStor) {
         long[] bDat = bStor.getRaw();
         int iterPerLong = 64 / bStor.getBits() - 1;
         int MSK = (1 << bStor.getBits()) - 1;
         int eBits = bStor.getBits();
         long sample = 0L;
         int c = 0;
         int dec = 0;

         for (int ix = 0; ix <= 4095; ix++) {
            if (dec-- == 0) {
               sample = bDat[c++];
               dec = iterPerLong;
            }

            int bId;
            if (bps == null) {
               bId = pc[Math.min((int)(sample & MSK), pcc)];
            } else {
               bId = stateMapper.getIdForBlockState((BlockState)bps.valueFor((int)(sample & MSK)));
            }

            sample >>>= eBits;
            byte light = lightSupplier.supply(ix & 15, ix >> 8 & 15, ix >> 4 & 15);
            i += bId != 0 ? 1 : 0;
            data[ix] = Mapper.composeMappingId(light, bId, biomes[Integer.compress(ix, 3276)]);
         }
      } else {
         if (!(blockContainer.data.storage() instanceof ZeroBitStorage)) {
            throw new IllegalStateException();
         }

         int bId = pc[0];
         if (bId == 0) {
            for (int ix = 0; ix <= 4095; ix++) {
               data[ix] = Mapper.airWithLight(lightSupplier.supply(ix & 15, ix >> 8 & 15, ix >> 4 & 15));
            }
         } else {
            i = 4096;

            for (int ix = 0; ix <= 4095; ix++) {
               byte light = lightSupplier.supply(ix & 15, ix >> 8 & 15, ix >> 4 & 15);
               data[ix] = Mapper.composeMappingId(light, bId, biomes[Integer.compress(ix, 3276)]);
            }
         }
      }

      section.lvl0NonAirCount = i;
      return section;
   }

   private static void computeZoomCells(int[] biomes, long zoomSeed, long[] zoomInfo) {
      for (int cy = 0; cy < 4; cy++) {
         for (int cz = 0; cz < 4; cz++) {
            int cx = 0;

            while (cx < 4) {
               cx++;
            }
         }
      }
   }

   @Deprecated(forRemoval = true)
   public static void mipSection(VoxelizedSection section, Mapper mapper) {
      WorldVoxilizedSectionMipper.mipSection(section, mapper);
   }

   private static final class Cache {
      private final int[] biomeCache = new int[64];
      private final WeakHashMap<Mapper, Reference2IntOpenHashMap<BlockState>> localMapping = new WeakHashMap<>();
      private int[] paletteCache = new int[1024];
      private final long[] zoomCellCache = new long[125];

      private Reference2IntOpenHashMap<BlockState> getLocalMapping(Mapper mapper) {
         return this.localMapping.computeIfAbsent(mapper, a_ -> new Reference2IntOpenHashMap());
      }

      private int[] getPaletteCache(int size) {
         if (this.paletteCache.length < size) {
            this.paletteCache = new int[size];
         }

         return this.paletteCache;
      }
   }
}
