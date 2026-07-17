package me.cortex.voxy.client.core.rendering.building;

import java.util.Arrays;
import java.util.Objects;
import me.cortex.voxy.client.core.model.IdNotYetComputedException;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.ModelQueries;
import me.cortex.voxy.client.core.util.ScanMesher2D;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import org.lwjgl.system.MemoryUtil;

public class RenderDataFactory {
   private static final boolean BUILD_OCCUPANCY_SET = false;
   private static final boolean CHECK_NEIGHBOR_FACE_OCCLUSION = true;
   private static final boolean DISABLE_CULL_SAME_OCCLUDES = false;
   private static final boolean VERIFY_MESHING = VoxyCommon.isVerificationFlagOn("verifyMeshing");
   private final WorldEngine world;
   private final ModelFactory modelMan;
   private final long[] sectionData = new long[65536];
   private final long[] neighboringFaces = new long[6144];
   private final int[] opaqueMasks = new int[1024];
   private final int[] nonOpaqueMasks = new int[1024];
   private final int[] fluidMasks = new int[1024];
   private final MemoryBuffer quadBuffer = new MemoryBuffer(4194304L);
   private final long quadBufferPtr = this.quadBuffer.address;
   private final int[] quadCounters = new int[8];
   private int minX;
   private int minY;
   private int minZ;
   private int maxX;
   private int maxY;
   private int maxZ;
   private int quadCount = 0;
   private final OccupancySet occupancy;
   private final RenderDataFactory.Mesher blockMesher = new RenderDataFactory.Mesher();
   private final RenderDataFactory.Mesher seondaryblockMesher = new RenderDataFactory.Mesher();
   private static final long LM = 9187343239835811840L;
   private final RenderDataFactory.Mesher[] xAxisMeshers = new RenderDataFactory.Mesher[32];
   private final RenderDataFactory.Mesher[] secondaryXAxisMeshers = new RenderDataFactory.Mesher[32];
   private static final long X_I_MSK = 1162219258676257L;

   public RenderDataFactory(WorldEngine world, ModelFactory modelManager, boolean emitMeshlets) {
      this(world, modelManager, emitMeshlets, false);
   }

   public RenderDataFactory(WorldEngine world, ModelFactory modelManager, boolean emitMeshlets, boolean generateOccupancy) {
      for (int i = 0; i < 32; i++) {
         RenderDataFactory.Mesher mesher = new RenderDataFactory.Mesher();
         mesher.auxiliaryPosition = i;
         mesher.axis = 2;
         this.xAxisMeshers[i] = mesher;
      }

      for (int i = 0; i < 32; i++) {
         RenderDataFactory.Mesher mesher = new RenderDataFactory.Mesher();
         mesher.auxiliaryPosition = i;
         mesher.axis = 2;
         mesher.doAuxiliaryFaceOffset = false;
         this.secondaryXAxisMeshers[i] = mesher;
      }

      this.world = world;
      this.modelMan = modelManager;
      if (generateOccupancy) {
      }

      this.occupancy = null;
   }

   private static long getQuadTyping(long metadata) {
      return 7L & 20L >> (int)(ModelQueries._isTranslucent(metadata) * 6L + ModelQueries._isDoubleSided(metadata) * 3L);
   }

   private static long packPartialQuadData(int modelId, long state, long metadata) {
      long lightAndBiome = (state & -140737488355328L) >>> 1;
      lightAndBiome &= ~(ModelQueries._notIsBiomeColoured(metadata) * 35958428274786304L);
      lightAndBiome &= ~(ModelQueries._isFullyOpaque(metadata) * 9187343239835811840L);
      long quadData = lightAndBiome | Integer.toUnsignedLong(modelId) << 26;
      return quadData | getQuadTyping(metadata);
   }

   private int prepareSectionData(long[] rawSectionData) {
      long[] sectionData = this.sectionData;
      int[] rawModelIds = this.modelMan._unsafeRawAccess();
      long opaque = 0L;
      long notEmpty = 0L;
      long pureFluid = 0L;
      long partialFluid = 0L;
      int neighborAcquireMskAndFlags = 0;
      int i = 0;

      for (int q = 0; q < 512; q++) {
         for (int j = 0; j < 64; j++) {
            long block = rawSectionData[i];
            if (Mapper.isAir(block)) {
               sectionData[i * 2] = (block & -72057594037927936L) >>> 1;
               sectionData[i * 2 + 1] = 0L;
            } else {
               int modelId = rawModelIds[Mapper.getBlockId(block)];
               if (modelId == -1) {
                  return Mapper.getBlockId(block) | -2147483648;
               }

               if (modelId == 0) {
                  sectionData[i * 2] = (block & -72057594037927936L) >>> 1;
                  sectionData[i * 2 + 1] = 0L;
               } else {
                  long modelMetadata = this.modelMan.getModelMetadataFromClientId(modelId);
                  sectionData[i * 2] = packPartialQuadData(modelId, block, modelMetadata);
                  sectionData[i * 2 + 1] = modelMetadata;
                  notEmpty |= 1L << j;
                  opaque |= ModelQueries._isFullyOpaque(modelMetadata) << j;
                  pureFluid |= ModelQueries._isFluid(modelMetadata) << j;
                  partialFluid |= ModelQueries._containsFluid(modelMetadata) << j;
               }
            }

            i++;
         }

         if (notEmpty != 0L) {
            long nonOpaque = (notEmpty ^ opaque) & ~pureFluid;
            long fluid = pureFluid | partialFluid;
            this.opaqueMasks[(i >> 5) - 2] = (int)opaque;
            this.opaqueMasks[(i >> 5) - 1] = (int)(opaque >>> 32);
            this.nonOpaqueMasks[(i >> 5) - 2] = (int)nonOpaque;
            this.nonOpaqueMasks[(i >> 5) - 1] = (int)(nonOpaque >>> 32);
            this.fluidMasks[(i >> 5) - 2] = (int)fluid;
            this.fluidMasks[(i >> 5) - 1] = (int)(fluid >>> 32);
            neighborAcquireMskAndFlags |= getNeighborMsk(notEmpty, i);
            neighborAcquireMskAndFlags |= opaque != 0L ? 64 : 0;
            opaque = 0L;
            notEmpty = 0L;
            pureFluid = 0L;
            partialFluid = 0L;
         }
      }

      return neighborAcquireMskAndFlags;
   }

   private static int getNeighborMsk(long notEmpty, int i) {
      int packedEmpty = (int)(notEmpty >>> 32 | notEmpty);
      int neighborMsk = 0;
      neighborMsk += packedEmpty & 1;
      neighborMsk += packedEmpty >>> 30 & 2;
      neighborMsk += (i - 1 >> 10 == 0 ? 4 : 0) * (packedEmpty != 0 ? 1 : 0);
      neighborMsk += (i - 1 >> 10 == 31 ? 8 : 0) * (packedEmpty != 0 ? 1 : 0);
      neighborMsk += ((i - 33 >> 5 & 31) == 0 ? 16 : 0) * ((int)notEmpty != 0 ? 1 : 0);
      return neighborMsk + ((i - 1 >> 5 & 31) == 31 ? 32 : 0) * (notEmpty >>> 32 != 0L ? 1 : 0);
   }

   private void acquireNeighborData(WorldSection section, int msk) {
      if ((msk & 1) != 0) {
         WorldSection sec = this.world.acquire(section.lvl, section.x - 1, section.y, section.z);
         long[] raw = sec._unsafeGetRawDataArray();

         for (int i = 0; i < 1024; i++) {
            this.neighboringFaces[i] = raw[(i << 5) + 31];
         }

         sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
      }

      if ((msk & 2) != 0) {
         WorldSection sec = this.world.acquire(section.lvl, section.x + 1, section.y, section.z);
         long[] raw = sec._unsafeGetRawDataArray();

         for (int i = 0; i < 1024; i++) {
            this.neighboringFaces[i + 1024] = raw[i << 5];
         }

         sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
      }

      if ((msk & 4) != 0) {
         WorldSection sec = this.world.acquire(section.lvl, section.x, section.y - 1, section.z);
         long[] raw = sec._unsafeGetRawDataArray();

         for (int i = 0; i < 1024; i++) {
            this.neighboringFaces[i + 2048] = raw[i | 31744];
         }

         sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
      }

      if ((msk & 8) != 0) {
         WorldSection sec = this.world.acquire(section.lvl, section.x, section.y + 1, section.z);
         long[] raw = sec._unsafeGetRawDataArray();

         for (int i = 0; i < 1024; i++) {
            this.neighboringFaces[i + 3072] = raw[i];
         }

         sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
      }

      if ((msk & 16) != 0) {
         WorldSection sec = this.world.acquire(section.lvl, section.x, section.y, section.z - 1);
         long[] raw = sec._unsafeGetRawDataArray();

         for (int i = 0; i < 1024; i++) {
            this.neighboringFaces[i + 4096] = raw[Integer.expand(i, 31775) | 992];
         }

         sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
      }

      if ((msk & 32) != 0) {
         WorldSection sec = this.world.acquire(section.lvl, section.x, section.y, section.z + 1);
         long[] raw = sec._unsafeGetRawDataArray();

         for (int i = 0; i < 1024; i++) {
            this.neighboringFaces[i + 5120] = raw[Integer.expand(i, 31775)];
         }

         sec.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
      }
   }

   private static boolean shouldMeshNonOpaqueBlockFace(int face, long quad, long meta, long neighborQuad, long neighborMeta) {
      if (((quad ^ neighborQuad) & 4397979402240L) == 0L && ModelQueries.cullsSame(meta)) {
         return false;
      } else {
         return !ModelQueries.faceExists(meta, face)
            ? false
            : !ModelQueries.faceCanBeOccluded(meta, face) || !ModelQueries.faceOccludes(neighborMeta, face ^ 1);
      }
   }

   private static void meshNonOpaqueFace(int face, long quad, long meta, long neighborQuad, long neighborMeta, RenderDataFactory.Mesher mesher) {
      if (shouldMeshNonOpaqueBlockFace(face, quad, meta, neighborQuad, neighborMeta)) {
         mesher.putNext(
            applyQuadLight(
               face & 1 | quad & -9187343239835811841L | (ModelQueries.faceUsesSelfLighting(meta, face) ? quad : neighborQuad) & 9187343239835811840L, meta
            )
         );
      } else {
         mesher.skip(1);
      }
   }

   private static long applyQuadLight(long quad, long selfmeta) {
      long BLMSK = 8646911284551352320L;
      long bl = quad & 8646911284551352320L;
      bl = Math.max(bl, ModelQueries.lightEmission(selfmeta) << 59);
      quad &= -8646911284551352321L;
      return quad | bl;
   }

   private void generateYZOpaqueInnerGeometry(int axis) {
      for (int layer = 0; layer < 31; layer++) {
         this.blockMesher.auxiliaryPosition = layer;
         int cSkip = 0;

         for (int other = 0; other < 32; other++) {
            int pidx = axis == 0 ? layer * 32 + other : other * 32 + layer;
            int skipAmount = axis == 0 ? 32 : 1;
            int current = this.opaqueMasks[pidx];
            int next = this.opaqueMasks[pidx + skipAmount];
            int msk = current ^ next;
            if (msk == 0) {
               cSkip += 32;
            } else {
               this.blockMesher.skip(cSkip);
               cSkip = 0;
               int faceForwardMsk = msk & current;
               int cIdx = -1;

               while (msk != 0) {
                  int index = Integer.numberOfTrailingZeros(msk);
                  int delta = index - cIdx - 1;
                  cIdx = index;
                  if (delta != 0) {
                     this.blockMesher.skip(delta);
                  }

                  msk &= ~Integer.lowestOneBit(msk);
                  int facingForward = faceForwardMsk >> index & 1;
                  int idx = index + pidx * 32;
                  int shift = skipAmount * 32 * 2;
                  int iA = idx * 2 + (facingForward == 1 ? 0 : shift);
                  int iB = idx * 2 + (facingForward == 1 ? shift : 0);
                  long selfModel = this.sectionData[iA];
                  long selfMeta = this.sectionData[iA + 1];
                  long nextModel = this.sectionData[iB];
                  long neighbor = this.sectionData[iB + 1];
                  boolean culls = false;
                  culls |= ((selfModel ^ nextModel) & 4397979402240L) == 0L && ModelQueries.cullsSame(neighbor);
                  culls |= ModelQueries.faceOccludes(neighbor, axis << 1 | 1 - facingForward);
                  if (culls) {
                     this.blockMesher.skip(1);
                  } else {
                     this.blockMesher.putNext(applyQuadLight(facingForward | selfModel & -9187343239835811841L | nextModel & 9187343239835811840L, selfMeta));
                  }
               }

               this.blockMesher.endRow();
            }
         }

         this.blockMesher.finish();
      }
   }

   private void generateYZOpaqueOuterGeometry(int axis) {
      this.blockMesher.doAuxiliaryFaceOffset = false;

      for (int side = 0; side < 2; side++) {
         int layer = side == 0 ? 0 : 31;
         this.blockMesher.auxiliaryPosition = layer;
         int cSkips = 0;

         for (int other = 0; other < 32; other++) {
            int pidx = axis == 0 ? layer * 32 + other : other * 32 + layer;
            int msk = this.opaqueMasks[pidx];
            if (msk == 0) {
               cSkips += 32;
            } else {
               this.blockMesher.skip(cSkips);
               cSkips = 0;
               int cIdx = -1;

               while (msk != 0) {
                  int index = Integer.numberOfTrailingZeros(msk);
                  int delta = index - cIdx - 1;
                  cIdx = index;
                  if (delta != 0) {
                     this.blockMesher.skip(delta);
                  }

                  msk &= ~Integer.lowestOneBit(msk);
                  int idx = index + pidx * 32;
                  int neighborIdx = (axis + 1) * 32 * 32 * 2 + side * 32 * 32;
                  long neighborId = this.neighboringFaces[neighborIdx + other * 32 + index];
                  long A = this.sectionData[idx * 2];
                  long selfMeta = this.sectionData[idx * 2 + 1];
                  int nib = Mapper.getBlockId(neighborId);
                  if (nib != 0) {
                     int cid = this.modelMan.getModelId(nib);
                     long meta = this.modelMan.getModelMetadataFromClientId(cid);
                     if (ModelQueries.isFullyOpaque(meta)) {
                        this.blockMesher.skip(1);
                        continue;
                     }

                     boolean culls = false;
                     culls |= cid == (A >> 26 & 65535L) && ModelQueries.cullsSame(meta);
                     culls |= ModelQueries.faceOccludes(meta, axis << 1 | 1 - side);
                     if (culls) {
                        this.blockMesher.skip(1);
                        continue;
                     }
                  }

                  this.blockMesher
                     .putNext(applyQuadLight((side == 0 ? 0L : 1L) | A & -9187343239835811841L | (neighborId & -72057594037927936L) >>> 1, selfMeta));
               }

               this.blockMesher.endRow();
            }
         }

         this.blockMesher.finish();
      }

      this.blockMesher.doAuxiliaryFaceOffset = true;
   }

   private void generateYZFluidInnerGeometry(int axis) {
      for (int layer = 0; layer < 31; layer++) {
         this.blockMesher.auxiliaryPosition = layer;
         int cSkip = 0;

         for (int other = 0; other < 32; other++) {
            int pidx = axis == 0 ? layer * 32 + other : other * 32 + layer;
            int skipAmount = axis == 0 ? 32 : 1;
            int current = this.fluidMasks[pidx];
            int next = this.fluidMasks[pidx + skipAmount];
            int msk = (current | this.opaqueMasks[pidx]) ^ (next | this.opaqueMasks[pidx + skipAmount]);
            msk &= current | next;
            if (msk == 0) {
               cSkip += 32;
            } else {
               this.blockMesher.skip(cSkip);
               cSkip = 0;
               int faceForwardMsk = msk & current;
               int cIdx = -1;

               while (msk != 0) {
                  int index = Integer.numberOfTrailingZeros(msk);
                  int delta = index - cIdx - 1;
                  cIdx = index;
                  if (delta != 0) {
                     this.blockMesher.skip(delta);
                  }

                  msk &= ~Integer.lowestOneBit(msk);
                  int facingForward = faceForwardMsk >> index & 1;
                  int idx = index + pidx * 32;
                  int a = idx * 2;
                  int b = (idx + skipAmount * 32) * 2;
                  int ai = facingForward == 1 ? a : b;
                  int bi = facingForward == 1 ? b : a;
                  if (ModelQueries.faceOccludes(this.sectionData[bi + 1], axis << 1 | 1 - facingForward)) {
                     this.blockMesher.skip(1);
                  } else {
                     long A = this.sectionData[ai];
                     long Am = this.sectionData[ai + 1];
                     if (ModelQueries.containsFluid(Am)) {
                        int modelId = (int)(A >> 26 & 65535L);
                        A &= -4397979402241L;
                        int fluidId = this.modelMan.getFluidClientStateId(modelId);
                        A |= Integer.toUnsignedLong(fluidId) << 26;
                        Am = this.modelMan.getModelMetadataFromClientId(fluidId);
                        A &= -7L;
                        A |= getQuadTyping(Am);
                     }

                     long lighter = this.sectionData[bi];
                     this.blockMesher.putNext(applyQuadLight(facingForward | A & -9187343239835811841L | lighter & 9187343239835811840L, Am));
                  }
               }

               this.blockMesher.endRow();
            }
         }

         this.blockMesher.finish();
      }
   }

   private void generateYZFluidOuterGeometry(int axis) {
      this.blockMesher.doAuxiliaryFaceOffset = false;

      for (int side = 0; side < 2; side++) {
         int layer = side == 0 ? 0 : 31;
         this.blockMesher.auxiliaryPosition = layer;
         int cSkips = 0;

         for (int other = 0; other < 32; other++) {
            int pidx = axis == 0 ? layer * 32 + other : other * 32 + layer;
            int msk = this.fluidMasks[pidx];
            if (msk == 0) {
               cSkips += 32;
            } else {
               this.blockMesher.skip(cSkips);
               cSkips = 0;
               int cIdx = -1;

               while (msk != 0) {
                  int index = Integer.numberOfTrailingZeros(msk);
                  int delta = index - cIdx - 1;
                  cIdx = index;
                  if (delta != 0) {
                     this.blockMesher.skip(delta);
                  }

                  msk &= ~Integer.lowestOneBit(msk);
                  int idx = index + pidx * 32;
                  int neighborIdx = (axis + 1) * 32 * 32 * 2 + side * 32 * 32;
                  long neighborId = this.neighboringFaces[neighborIdx + other * 32 + index];
                  long A = this.sectionData[idx * 2];
                  long Am = this.sectionData[idx * 2 + 1];
                  if (ModelQueries.containsFluid(Am)) {
                     int modelId = (int)(A >> 26 & 65535L);
                     A &= -4397979402241L;
                     int fluidId = this.modelMan.getFluidClientStateId(modelId);
                     A |= Integer.toUnsignedLong(fluidId) << 26;
                     Am = this.modelMan.getModelMetadataFromClientId(fluidId);
                     A &= -7L;
                     A |= getQuadTyping(Am);
                  }

                  if (Mapper.getBlockId(neighborId) != 0) {
                     int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                     long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                     if (ModelQueries.containsFluid(meta)) {
                        modelId = this.modelMan.getFluidClientStateId(modelId);
                     }

                     if (ModelQueries.cullsSame(Am) && modelId == (A >> 26 & 65535L)) {
                        this.blockMesher.skip(1);
                        continue;
                     }

                     if (ModelQueries.faceOccludes(meta, axis << 1 | 1 - side)) {
                        this.blockMesher.skip(1);
                        continue;
                     }
                  }

                  this.blockMesher.putNext(applyQuadLight((side == 0 ? 0L : 1L) | A & -9187343239835811841L | (neighborId & -72057594037927936L) >>> 1, Am));
               }

               this.blockMesher.endRow();
            }
         }

         this.blockMesher.finish();
      }

      this.blockMesher.doAuxiliaryFaceOffset = true;
   }

   private void generateYZNonOpaqueInnerGeometry(int axis) {
      this.seondaryblockMesher.doAuxiliaryFaceOffset = false;
      this.blockMesher.axis = axis;
      this.seondaryblockMesher.axis = axis;

      for (int layer = 1; layer < 31; layer++) {
         this.blockMesher.auxiliaryPosition = layer;
         this.seondaryblockMesher.auxiliaryPosition = layer;
         int cSkip = 0;

         for (int other = 0; other < 32; other++) {
            int pidx = axis == 0 ? layer * 32 + other : other * 32 + layer;
            int skipAmount = axis == 0 ? 1024 : 32;
            int msk = this.nonOpaqueMasks[pidx];
            if (msk == 0) {
               cSkip += 32;
            } else {
               this.blockMesher.skip(cSkip);
               this.seondaryblockMesher.skip(cSkip);
               cSkip = 0;
               int cIdx = -1;

               while (msk != 0) {
                  int index = Integer.numberOfTrailingZeros(msk);
                  int delta = index - cIdx - 1;
                  cIdx = index;
                  if (delta != 0) {
                     this.blockMesher.skip(delta);
                     this.seondaryblockMesher.skip(delta);
                  }

                  msk &= ~Integer.lowestOneBit(msk);
                  int idx = index + pidx * 32;
                  long A = this.sectionData[idx * 2];
                  long B = this.sectionData[idx * 2 + 1];
                  meshNonOpaqueFace(
                     axis << 1 | 0, A, B, this.sectionData[(idx - skipAmount) * 2], this.sectionData[(idx - skipAmount) * 2 + 1], this.seondaryblockMesher
                  );
                  meshNonOpaqueFace(
                     axis << 1 | 1, A, B, this.sectionData[(idx + skipAmount) * 2], this.sectionData[(idx + skipAmount) * 2 + 1], this.blockMesher
                  );
               }

               this.blockMesher.endRow();
               this.seondaryblockMesher.endRow();
            }
         }

         this.blockMesher.finish();
         this.seondaryblockMesher.finish();
      }
   }

   private void generateYZNonOpaqueOuterGeometry(int axis) {
      this.seondaryblockMesher.doAuxiliaryFaceOffset = false;
      this.blockMesher.axis = axis;
      this.seondaryblockMesher.axis = axis;

      for (int side = 0; side < 2; side++) {
         int layer = side == 0 ? 0 : 31;
         int skipAmount = (axis == 0 ? 1024 : 32) * (1 - side * 2);
         this.blockMesher.auxiliaryPosition = layer;
         this.seondaryblockMesher.auxiliaryPosition = layer;
         int cSkips = 0;

         for (int other = 0; other < 32; other++) {
            int pidx = axis == 0 ? layer * 32 + other : other * 32 + layer;
            int msk = this.nonOpaqueMasks[pidx];
            if (msk == 0) {
               cSkips += 32;
            } else {
               this.blockMesher.skip(cSkips);
               this.seondaryblockMesher.skip(cSkips);
               cSkips = 0;
               int cIdx = -1;

               while (msk != 0) {
                  int index = Integer.numberOfTrailingZeros(msk);
                  int delta = index - cIdx - 1;
                  cIdx = index;
                  if (delta != 0) {
                     this.blockMesher.skip(delta);
                     this.seondaryblockMesher.skip(delta);
                  }

                  msk &= ~Integer.lowestOneBit(msk);
                  int idx = index + pidx * 32;
                  int neighborIdx = (axis + 1) * 32 * 32 * 2 + side * 32 * 32;
                  long neighborId = this.neighboringFaces[neighborIdx + other * 32 + index];
                  long A = this.sectionData[idx * 2];
                  long Am = this.sectionData[idx * 2 + 1];
                  boolean fail = false;
                  if (Mapper.getBlockId(neighborId) != 0) {
                     int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                     if (ModelQueries.cullsSame(Am) && modelId == (A >> 26 & 65535L)) {
                        fail = true;
                     } else {
                        long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                        if (ModelQueries.faceOccludes(meta, axis << 1 | 1 - side)) {
                           fail = true;
                        }
                     }
                  }

                  long nA = this.sectionData[(idx + skipAmount) * 2];
                  long nB = this.sectionData[(idx + skipAmount) * 2 + 1];
                  boolean failB = false;
                  if (ModelQueries.cullsSame(nB) && (nA & 4397979402240L) == (A & 4397979402240L)) {
                     failB = true;
                  } else if (ModelQueries.faceOccludes(nB, axis << 1 | side)) {
                     failB = true;
                  }

                  if (!ModelQueries.faceExists(Am, axis << 1 | 1) || (side != 1 || fail) && (side != 0 || failB)) {
                     this.blockMesher.skip(1);
                  } else {
                     this.blockMesher.putNext(applyQuadLight(1L | A | 0L, Am));
                  }

                  if (!ModelQueries.faceExists(Am, axis << 1 | 0) || (side != 0 || fail) && (side != 1 || failB)) {
                     this.seondaryblockMesher.skip(1);
                  } else {
                     this.seondaryblockMesher.putNext(applyQuadLight(0L | A | 0L, Am));
                  }
               }

               this.blockMesher.endRow();
               this.seondaryblockMesher.endRow();
            }
         }

         this.blockMesher.finish();
         this.seondaryblockMesher.finish();
      }
   }

   private void generateYZFaces() {
      for (int axis = 0; axis < 2; axis++) {
         this.blockMesher.axis = axis;
         this.generateYZOpaqueInnerGeometry(axis);
         this.generateYZOpaqueOuterGeometry(axis);
         this.generateYZFluidInnerGeometry(axis);
         this.generateYZFluidOuterGeometry(axis);
         this.generateYZNonOpaqueInnerGeometry(axis);
         this.generateYZNonOpaqueOuterGeometry(axis);
      }
   }

   private void generateXOpaqueInnerGeometry() {
      for (int y = 0; y < 32; y++) {
         long sumA = 0L;
         long sumB = 0L;
         long sumC = 0L;
         int partialHasCount = -1;
         int msk = 0;

         for (int z = 0; z < 32; z++) {
            int lMsk = this.opaqueMasks[y * 32 + z];
            int var27 = lMsk ^ lMsk >>> 1;
            msk = var27 & 2147483647;
            sumA += 1162219258676257L;
            sumB += 1162219258676257L;
            sumC += 1162219258676257L;
            partialHasCount &= ~msk;
            if (z == 30 && partialHasCount != 0) {
               int cmsk = partialHasCount;

               while (cmsk != 0) {
                  int index = Integer.numberOfTrailingZeros(cmsk);
                  cmsk &= ~Integer.lowestOneBit(cmsk);
                  this.xAxisMeshers[index].skip(31);
               }

               sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), 1162219258676257L) * 31L);
               sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 11, 1162219258676257L) * 31L);
               sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 22, 1162219258676257L) * 31L);
            }

            if (msk != 0) {
               int faceForwardMsk = msk & lMsk;
               int iter = msk;

               while (iter != 0) {
                  int index = Integer.numberOfTrailingZeros(iter);
                  iter &= ~Integer.lowestOneBit(iter);
                  RenderDataFactory.Mesher mesher = this.xAxisMeshers[index];
                  int skipCount;
                  if (index < 11) {
                     skipCount = (int)(sumA >> index * 5);
                     sumA &= ~(31L << index * 5);
                  } else if (index < 22) {
                     skipCount = (int)(sumB >> (index - 11) * 5);
                     sumB &= ~(31L << (index - 11) * 5);
                  } else {
                     skipCount = (int)(sumC >> (index - 22) * 5);
                     sumC &= ~(31L << (index - 22) * 5);
                  }

                  skipCount &= 31;
                  if (--skipCount != 0) {
                     mesher.skip(skipCount);
                  }

                  int facingForward = faceForwardMsk >> index & 1;
                  int idx = index + z * 32 + y * 32 * 32;
                  int iA = idx * 2 + (facingForward == 1 ? 0 : 2);
                  int iB = idx * 2 + (facingForward == 1 ? 2 : 0);
                  if (ModelQueries.faceOccludes(this.sectionData[iB + 1], 4 | 1 - facingForward)) {
                     mesher.skip(1);
                  } else {
                     long selfModel = this.sectionData[iA];
                     long selfMeta = this.sectionData[iA + 1];
                     long nextModel = this.sectionData[iB];
                     mesher.putNext(applyQuadLight(facingForward | selfModel & -9187343239835811841L | nextModel & 9187343239835811840L, selfMeta));
                  }
               }
            }
         }

         msk = ~msk;

         while (msk != 0) {
            int indexx = Integer.numberOfTrailingZeros(msk);
            msk &= ~Integer.lowestOneBit(msk);
            int skipCountx;
            if (indexx < 11) {
               skipCountx = (int)(sumA >> indexx * 5);
            } else if (indexx < 22) {
               skipCountx = (int)(sumB >> (indexx - 11) * 5);
            } else {
               skipCountx = (int)(sumC >> (indexx - 22) * 5);
            }

            skipCountx &= 31;
            if (skipCountx != 0) {
               this.xAxisMeshers[indexx].skip(skipCountx);
            }
         }
      }
   }

   private void generateXOuterOpaqueGeometry() {
      RenderDataFactory.Mesher ma = this.xAxisMeshers[0];
      RenderDataFactory.Mesher mb = this.xAxisMeshers[31];
      ma.finish();
      mb.finish();
      ma.doAuxiliaryFaceOffset = false;
      mb.doAuxiliaryFaceOffset = false;

      for (int y = 0; y < 32; y++) {
         int skipA = 0;
         int skipB = 0;

         for (int z = 0; z < 32; z++) {
            int i = y * 32 + z;
            int msk = this.opaqueMasks[i];
            if ((msk & 1) != 0) {
               long neighborId = this.neighboringFaces[i];
               boolean oki = true;
               if (Mapper.getBlockId(neighborId) != 0) {
                  long meta = this.modelMan.getModelMetadataFromClientId(this.modelMan.getModelId(Mapper.getBlockId(neighborId)));
                  if (ModelQueries.isFullyOpaque(meta)) {
                     oki = false;
                  } else if (ModelQueries.faceOccludes(meta, 5)) {
                     oki = false;
                  }
               }

               if (oki) {
                  ma.skip(skipA);
                  skipA = 0;
                  long A = this.sectionData[(i << 5) * 2];
                  long Am = this.sectionData[(i << 5) * 2 + 1];
                  ma.putNext(applyQuadLight(0L | A & -9187343239835811841L | (neighborId & -72057594037927936L) >>> 1, Am));
               } else {
                  skipA++;
               }
            } else {
               skipA++;
            }

            if ((msk & -2147483648) != 0) {
               long neighborIdx = this.neighboringFaces[i + 1024];
               boolean okix = true;
               if (Mapper.getBlockId(neighborIdx) != 0) {
                  long meta = this.modelMan.getModelMetadataFromClientId(this.modelMan.getModelId(Mapper.getBlockId(neighborIdx)));
                  if (ModelQueries.isFullyOpaque(meta)) {
                     okix = false;
                  } else if (ModelQueries.faceOccludes(meta, 4)) {
                     okix = false;
                  }
               }

               if (okix) {
                  mb.skip(skipB);
                  skipB = 0;
                  long A = this.sectionData[(i * 32 + 31) * 2];
                  long Am = this.sectionData[(i * 32 + 31) * 2 + 1];
                  mb.putNext(applyQuadLight(1L | A & -9187343239835811841L | (neighborIdx & -72057594037927936L) >>> 1, Am));
               } else {
                  skipB++;
               }
            } else {
               skipB++;
            }
         }

         ma.skip(skipA);
         mb.skip(skipB);
      }

      ma.finish();
      mb.finish();
      ma.doAuxiliaryFaceOffset = true;
      mb.doAuxiliaryFaceOffset = true;
   }

   private void generateXInnerFluidGeometry() {
      for (int y = 0; y < 32; y++) {
         long sumA = 0L;
         long sumB = 0L;
         long sumC = 0L;
         int partialHasCount = -1;
         int msk = 0;

         for (int z = 0; z < 32; z++) {
            int oMsk = this.opaqueMasks[y * 32 + z];
            int fMsk = this.fluidMasks[y * 32 + z];
            int lMsk = oMsk | fMsk;
            int var29 = lMsk ^ lMsk >>> 1;
            int var30 = var29 & 2147483647;
            msk = var30 & (fMsk | fMsk >> 1);
            sumA += 1162219258676257L;
            sumB += 1162219258676257L;
            sumC += 1162219258676257L;
            partialHasCount &= ~msk;
            if (z == 30 && partialHasCount != 0) {
               int cmsk = partialHasCount;

               while (cmsk != 0) {
                  int index = Integer.numberOfTrailingZeros(cmsk);
                  cmsk &= ~Integer.lowestOneBit(cmsk);
                  this.xAxisMeshers[index].skip(31);
               }

               sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), 1162219258676257L) * 31L);
               sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 11, 1162219258676257L) * 31L);
               sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 22, 1162219258676257L) * 31L);
            }

            if (msk != 0) {
               int faceForwardMsk = msk & lMsk;
               int iter = msk;

               while (iter != 0) {
                  int index = Integer.numberOfTrailingZeros(iter);
                  iter &= ~Integer.lowestOneBit(iter);
                  RenderDataFactory.Mesher mesher = this.xAxisMeshers[index];
                  int skipCount;
                  if (index < 11) {
                     skipCount = (int)(sumA >> index * 5);
                     sumA &= ~(31L << index * 5);
                  } else if (index < 22) {
                     skipCount = (int)(sumB >> (index - 11) * 5);
                     sumB &= ~(31L << (index - 11) * 5);
                  } else {
                     skipCount = (int)(sumC >> (index - 22) * 5);
                     sumC &= ~(31L << (index - 22) * 5);
                  }

                  skipCount &= 31;
                  if (--skipCount != 0) {
                     mesher.skip(skipCount);
                  }

                  int facingForward = faceForwardMsk >> index & 1;
                  int idx = index + z * 32 + y * 32 * 32;
                  int ai = (idx + (1 - facingForward)) * 2;
                  int bi = (idx + facingForward) * 2;
                  if (ModelQueries.faceOccludes(this.sectionData[bi + 1], 4 | 1 - facingForward)) {
                     mesher.skip(1);
                  } else {
                     long A = this.sectionData[ai];
                     long Am = this.sectionData[ai + 1];
                     if (ModelQueries.containsFluid(Am)) {
                        int modelId = (int)(A >> 26 & 65535L);
                        A &= -4397979402241L;
                        int fluidId = this.modelMan.getFluidClientStateId(modelId);
                        A |= Integer.toUnsignedLong(fluidId) << 26;
                        Am = this.modelMan.getModelMetadataFromClientId(fluidId);
                        A &= -7L;
                        A |= getQuadTyping(Am);
                     }

                     long lighter = this.sectionData[bi];
                     mesher.putNext(applyQuadLight(facingForward | A & -9187343239835811841L | lighter & 9187343239835811840L, Am));
                  }
               }
            }
         }

         msk = ~msk;

         while (msk != 0) {
            int indexx = Integer.numberOfTrailingZeros(msk);
            msk &= ~Integer.lowestOneBit(msk);
            int skipCountx;
            if (indexx < 11) {
               skipCountx = (int)(sumA >> indexx * 5);
            } else if (indexx < 22) {
               skipCountx = (int)(sumB >> (indexx - 11) * 5);
            } else {
               skipCountx = (int)(sumC >> (indexx - 22) * 5);
            }

            skipCountx &= 31;
            if (skipCountx != 0) {
               this.xAxisMeshers[indexx].skip(skipCountx);
            }
         }
      }
   }

   private void generateXOuterFluidGeometry() {
      RenderDataFactory.Mesher ma = this.xAxisMeshers[0];
      RenderDataFactory.Mesher mb = this.xAxisMeshers[31];
      ma.finish();
      mb.finish();
      ma.doAuxiliaryFaceOffset = false;
      mb.doAuxiliaryFaceOffset = false;

      for (int y = 0; y < 32; y++) {
         int skipA = 0;
         int skipB = 0;

         for (int z = 0; z < 32; z++) {
            int i = y * 32 + z;
            int msk = this.fluidMasks[i];
            if ((msk & 1) != 0) {
               long neighborId = this.neighboringFaces[i];
               boolean oki = true;
               int sidx = (i << 5) * 2;
               long A = this.sectionData[sidx];
               long Am = this.sectionData[sidx + 1];
               if (ModelQueries.containsFluid(Am)) {
                  int modelId = (int)(A >> 26 & 65535L);
                  A &= -4397979402241L;
                  int fluidId = this.modelMan.getFluidClientStateId(modelId);
                  A |= Integer.toUnsignedLong(fluidId) << 26;
                  Am = this.modelMan.getModelMetadataFromClientId(fluidId);
                  A &= -7L;
                  A |= getQuadTyping(Am);
               }

               if (Mapper.getBlockId(neighborId) != 0) {
                  int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                  long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                  if (ModelQueries.isFullyOpaque(meta)) {
                     oki = false;
                  }

                  if (ModelQueries.faceOccludes(meta, 5)) {
                     oki = false;
                  }

                  if (ModelQueries.containsFluid(meta)) {
                     modelId = this.modelMan.getFluidClientStateId(modelId);
                  }

                  if (ModelQueries.cullsSame(Am) && modelId == (A >> 26 & 65535L)) {
                     oki = false;
                  }
               }

               if (oki) {
                  ma.skip(skipA);
                  skipA = 0;
                  long lightData = (neighborId & -72057594037927936L) >>> 1;
                  ma.putNext(applyQuadLight(0L | A & -9187343239835811841L | lightData, Am));
               } else {
                  skipA++;
               }
            } else {
               skipA++;
            }

            if ((msk & -2147483648) != 0) {
               long neighborIdx = this.neighboringFaces[i + 1024];
               boolean okix = true;
               int sidxx = (i * 32 + 31) * 2;
               long Ax = this.sectionData[sidxx];
               long Amx = this.sectionData[sidxx + 1];
               if (ModelQueries.containsFluid(Amx)) {
                  int modelIdx = (int)(Ax >> 26 & 65535L);
                  Ax &= -4397979402241L;
                  int fluidId = this.modelMan.getFluidClientStateId(modelIdx);
                  Ax |= Integer.toUnsignedLong(fluidId) << 26;
                  Amx = this.modelMan.getModelMetadataFromClientId(fluidId);
               }

               if (Mapper.getBlockId(neighborIdx) != 0) {
                  int modelIdx = this.modelMan.getModelId(Mapper.getBlockId(neighborIdx));
                  long metax = this.modelMan.getModelMetadataFromClientId(modelIdx);
                  if (ModelQueries.isFullyOpaque(metax)) {
                     okix = false;
                  }

                  if (ModelQueries.faceOccludes(metax, 4)) {
                     okix = false;
                  }

                  if (ModelQueries.containsFluid(metax)) {
                     modelIdx = this.modelMan.getFluidClientStateId(modelIdx);
                  }

                  if (ModelQueries.cullsSame(Amx) && modelIdx == (Ax >> 26 & 65535L)) {
                     okix = false;
                  }
               }

               if (okix) {
                  mb.skip(skipB);
                  skipB = 0;
                  long lightData = (neighborIdx & -72057594037927936L) >>> 1;
                  mb.putNext(applyQuadLight(1L | Ax & -9187343239835811841L | lightData, Amx));
               } else {
                  skipB++;
               }
            } else {
               skipB++;
            }
         }

         ma.skip(skipA);
         mb.skip(skipB);
      }

      ma.finish();
      mb.finish();
      ma.doAuxiliaryFaceOffset = true;
      mb.doAuxiliaryFaceOffset = true;
   }

   private void generateXNonOpaqueInnerGeometry() {
      for (int y = 0; y < 32; y++) {
         long sumA = 0L;
         long sumB = 0L;
         long sumC = 0L;
         int partialHasCount = -1;
         int msk = 0;

         for (int z = 0; z < 32; z++) {
            msk = this.nonOpaqueMasks[y * 32 + z] & 2147483646;
            sumA += 1162219258676257L;
            sumB += 1162219258676257L;
            sumC += 1162219258676257L;
            partialHasCount &= ~msk;
            if (z == 30 && partialHasCount != 0) {
               int cmsk = partialHasCount;

               while (cmsk != 0) {
                  int index = Integer.numberOfTrailingZeros(cmsk);
                  cmsk &= ~Integer.lowestOneBit(cmsk);
                  this.xAxisMeshers[index].skip(31);
                  this.secondaryXAxisMeshers[index].skip(31);
               }

               sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), 1162219258676257L) * 31L);
               sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 11, 1162219258676257L) * 31L);
               sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount) >> 22, 1162219258676257L) * 31L);
            }

            if (msk != 0) {
               int iter = msk;

               while (iter != 0) {
                  int index = Integer.numberOfTrailingZeros(iter);
                  iter &= ~Integer.lowestOneBit(iter);
                  int skipCount;
                  if (index < 11) {
                     skipCount = (int)(sumA >> index * 5);
                     sumA &= ~(31L << index * 5);
                  } else if (index < 22) {
                     skipCount = (int)(sumB >> (index - 11) * 5);
                     sumB &= ~(31L << (index - 11) * 5);
                  } else {
                     skipCount = (int)(sumC >> (index - 22) * 5);
                     sumC &= ~(31L << (index - 22) * 5);
                  }

                  skipCount &= 31;
                  skipCount--;
                  RenderDataFactory.Mesher mesherA = this.xAxisMeshers[index];
                  RenderDataFactory.Mesher mesherB = this.secondaryXAxisMeshers[index];
                  if (skipCount != 0) {
                     mesherA.skip(skipCount);
                     mesherB.skip(skipCount);
                  }

                  int idx = index + z * 32 + y * 32 * 32;
                  long A = this.sectionData[idx * 2];
                  long Am = this.sectionData[idx * 2 + 1];
                  meshNonOpaqueFace(4, A, Am, this.sectionData[(idx - 1) * 2], this.sectionData[(idx - 1) * 2 + 1], mesherB);
                  meshNonOpaqueFace(5, A, Am, this.sectionData[(idx + 1) * 2], this.sectionData[(idx + 1) * 2 + 1], mesherA);
               }
            }
         }

         msk = ~msk;

         while (msk != 0) {
            int indexx = Integer.numberOfTrailingZeros(msk);
            msk &= ~Integer.lowestOneBit(msk);
            int skipCountx;
            if (indexx < 11) {
               skipCountx = (int)(sumA >> indexx * 5);
            } else if (indexx < 22) {
               skipCountx = (int)(sumB >> (indexx - 11) * 5);
            } else {
               skipCountx = (int)(sumC >> (indexx - 22) * 5);
            }

            skipCountx &= 31;
            if (skipCountx != 0) {
               this.xAxisMeshers[indexx].skip(skipCountx);
               this.secondaryXAxisMeshers[indexx].skip(skipCountx);
            }
         }
      }
   }

   private static void dualMeshNonOpaqueOuterX(
      int side,
      long quad,
      long meta,
      int neighborAId,
      int neighborLight,
      long neighborAMeta,
      long neighborBQuad,
      long neighborBMeta,
      RenderDataFactory.Mesher ma,
      RenderDataFactory.Mesher mb
   ) {
      if (neighborAId == 0 && ModelQueries.faceExists(meta, 4 ^ side)
         || neighborAId != 0 && shouldMeshNonOpaqueBlockFace(4 ^ side, quad, meta, (long)neighborAId << 26, neighborAMeta)) {
         ma.putNext(
            applyQuadLight(side | quad & -9187343239835811841L | (ModelQueries.faceUsesSelfLighting(meta, 4 ^ side) ? quad : (long)neighborLight << 55), meta)
         );
      } else {
         ma.skip(1);
      }

      if (shouldMeshNonOpaqueBlockFace(5 ^ side, quad, meta, neighborBQuad, neighborBMeta)) {
         mb.putNext(
            applyQuadLight(
               side ^ 1 | quad & -9187343239835811841L | (ModelQueries.faceUsesSelfLighting(meta, 5 ^ side) ? quad : neighborBQuad) & 9187343239835811840L,
               meta
            )
         );
      } else {
         mb.skip(1);
      }
   }

   private void generateXNonOpaqueOuterGeometry() {
      RenderDataFactory.Mesher npx = this.xAxisMeshers[0];
      npx.finish();
      RenderDataFactory.Mesher nnx = this.secondaryXAxisMeshers[0];
      nnx.finish();
      RenderDataFactory.Mesher ppx = this.xAxisMeshers[31];
      ppx.finish();
      RenderDataFactory.Mesher pnx = this.secondaryXAxisMeshers[31];
      pnx.finish();

      for (int y = 0; y < 32; y++) {
         int skipA = 0;
         int skipB = 0;

         for (int z = 0; z < 32; z++) {
            int i = y * 32 + z;
            int msk = this.nonOpaqueMasks[i];
            if ((msk & 1) != 0) {
               long neighborId = this.neighboringFaces[i];
               int sidx = (i << 5) * 2;
               long A = this.sectionData[sidx];
               long Am = this.sectionData[sidx + 1];
               int modelId = 0;
               long nM = 0L;
               if (Mapper.getBlockId(neighborId) != 0) {
                  modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                  nM = this.modelMan.getModelMetadataFromClientId(modelId);
               }

               nnx.skip(skipA);
               npx.skip(skipA);
               skipA = 0;
               dualMeshNonOpaqueOuterX(0, A, Am, modelId, Mapper.getLightId(neighborId), nM, this.sectionData[sidx + 2], this.sectionData[sidx + 3], nnx, npx);
            } else {
               skipA++;
            }

            if ((msk & -2147483648) != 0) {
               long neighborId = this.neighboringFaces[i + 1024];
               int sidx = (i * 32 + 31) * 2;
               long A = this.sectionData[sidx];
               long Am = this.sectionData[sidx + 1];
               int modelId = 0;
               long nM = 0L;
               if (Mapper.getBlockId(neighborId) != 0) {
                  modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                  nM = this.modelMan.getModelMetadataFromClientId(modelId);
               }

               pnx.skip(skipB);
               ppx.skip(skipB);
               skipB = 0;
               dualMeshNonOpaqueOuterX(1, A, Am, modelId, Mapper.getLightId(neighborId), nM, this.sectionData[sidx - 2], this.sectionData[sidx - 1], ppx, pnx);
            } else {
               skipB++;
            }
         }

         nnx.skip(skipA);
         npx.skip(skipA);
         pnx.skip(skipB);
         ppx.skip(skipB);
      }
   }

   private void generateXFaces() {
      this.generateXOpaqueInnerGeometry();
      this.generateXOuterOpaqueGeometry();

      for (RenderDataFactory.Mesher mesher : this.xAxisMeshers) {
         mesher.finish();
      }

      this.generateXInnerFluidGeometry();
      this.generateXOuterFluidGeometry();

      for (RenderDataFactory.Mesher mesher : this.xAxisMeshers) {
         mesher.finish();
      }

      this.generateXNonOpaqueInnerGeometry();
      this.generateXNonOpaqueOuterGeometry();

      for (RenderDataFactory.Mesher mesher : this.xAxisMeshers) {
         mesher.finish();
      }

      for (RenderDataFactory.Mesher mesher : this.secondaryXAxisMeshers) {
         mesher.finish();
      }
   }

   private final int occupancyBarrier(int index) {
      int occ = 0;
      int msk = this.opaqueMasks[index];
      occ |= msk ^ msk >> 1;
      occ |= msk ^ msk << 1;
      occ |= index < 992 ? msk ^ this.opaqueMasks[index + 32] : 0;
      occ |= 31 < index ? msk ^ this.opaqueMasks[index - 32] : 0;
      occ |= (index & 31) < 31 ? msk ^ this.opaqueMasks[index + 1] : 0;
      return occ | (0 < (index & 31) ? msk ^ this.opaqueMasks[index - 1] : 0);
   }

   private final void buildOccupancy() {
      for (int i = 0; i < 1024; i++) {
         for (int occ = this.occupancyBarrier(i); occ != 0; occ &= ~Integer.lowestOneBit(occ)) {
            this.occupancy.set(i * 32 + Integer.numberOfTrailingZeros(occ));
         }
      }
   }

   private final void buildOccupancy16() {
      for (int i = 0; i < 256; i++) {
         int x = (i & 15) * 2;
         int y = (i >> 4) * 2;
         int A = this.occupancyBarrier(y * 32 + x);
         A = (A | A >> 16) & 65535;
         int B = this.occupancyBarrier(y * 32 + x + 1);
         B = (B | B >> 16) & 65535;
         int C = this.occupancyBarrier((y + 1) * 32 + x);
         C = (C | C >> 16) & 65535;
         int D = this.occupancyBarrier((y + 1) * 32 + x + 1);
         D = (D | D >> 16) & 65535;

         for (int occ = A | B | C | D; occ != 0; occ &= ~Integer.lowestOneBit(occ)) {
            this.occupancy.set(i * 16 + Integer.numberOfTrailingZeros(occ));
         }
      }
   }

   public BuiltSection generateMesh(WorldSection section) {
      this.quadCount = 0;
      this.blockMesher.reset();
      this.blockMesher.doAuxiliaryFaceOffset = true;
      this.seondaryblockMesher.reset();
      this.seondaryblockMesher.doAuxiliaryFaceOffset = true;

      for (RenderDataFactory.Mesher mesher : this.xAxisMeshers) {
         mesher.reset();
         mesher.doAuxiliaryFaceOffset = true;
      }

      for (RenderDataFactory.Mesher mesher : this.secondaryXAxisMeshers) {
         mesher.reset();
         mesher.doAuxiliaryFaceOffset = false;
      }

      if (this.occupancy != null) {
         this.occupancy.reset();
      }

      this.minX = Integer.MAX_VALUE;
      this.minY = Integer.MAX_VALUE;
      this.minZ = Integer.MAX_VALUE;
      this.maxX = Integer.MIN_VALUE;
      this.maxY = Integer.MIN_VALUE;
      this.maxZ = Integer.MIN_VALUE;
      Arrays.fill(this.quadCounters, 0);
      Arrays.fill(this.opaqueMasks, 0);
      Arrays.fill(this.nonOpaqueMasks, 0);
      Arrays.fill(this.fluidMasks, 0);
      int neighborMskAndFlags = this.prepareSectionData(section._unsafeGetRawDataArray());
      if ((neighborMskAndFlags & -2147483648) != 0) {
         throw new IdNotYetComputedException(neighborMskAndFlags & 1048575, true);
      } else {
         int neighborMsk = neighborMskAndFlags & 63;
         int flags = neighborMskAndFlags >>> 6;
         this.acquireNeighborData(section, neighborMsk);

         try {
            this.generateYZFaces();
            this.generateXFaces();
         } catch (IdNotYetComputedException var12) {
            var12.auxBitMsk = neighborMsk;
            var12.auxData = this.neighboringFaces;
            throw var12;
         }

         if (this.occupancy != null && section.lvl == 0 && this.quadCount != 0 && (flags & 1) != 0) {
            this.buildOccupancy();
         }

         if (this.quadCount == 0) {
            return BuiltSection.emptyWithChildren(section.key, section.getNonEmptyChildren());
         } else {
            if (this.quadCount >= 65536) {
               Logger.warn("Large quad count for section " + WorldEngine.pprintPos(section.key) + " is " + this.quadCount);
            }

            if (this.minX >= 0 && this.minY >= 0 && this.minZ >= 0 && 32 >= this.maxX && 32 >= this.maxY && 32 >= this.maxZ) {
               int[] offsets = new int[8];
               MemoryBuffer buff = new MemoryBuffer(this.quadCount * 8L);
               long ptr = buff.address;
               int coff = 0;

               for (int buffer = 0; buffer < 8; buffer++) {
                  offsets[buffer] = coff;
                  int size = this.quadCounters[buffer];
                  UnsafeUtil.memcpy(this.quadBufferPtr + buffer * 524288, ptr + coff * 8L, size * 8L);
                  coff += size;
               }

               int aabb = 0;
               aabb |= this.minX;
               aabb |= this.minY << 5;
               aabb |= this.minZ << 10;
               aabb |= Math.max(0, this.maxX - this.minX - 1) << 15;
               aabb |= Math.max(0, this.maxY - this.minY - 1) << 20;
               aabb |= Math.max(0, this.maxZ - this.minZ - 1) << 25;
               MemoryBuffer occupancy = null;
               if (this.occupancy != null && !this.occupancy.isEmpty()) {
                  occupancy = new MemoryBuffer(this.occupancy.writeSize());
                  this.occupancy.write(occupancy.address, false);
               }

               return new BuiltSection(section.key, section.getNonEmptyChildren(), aabb, buff, offsets, occupancy);
            } else {
               throw new IllegalStateException();
            }
         }
      }
   }

   public void free() {
      this.quadBuffer.free();
   }

   private final class Mesher extends ScanMesher2D {
      public int auxiliaryPosition;
      public boolean doAuxiliaryFaceOffset;
      public int axis;

      private Mesher() {
         Objects.requireNonNull(RenderDataFactory.this);
         super();
         this.auxiliaryPosition = 0;
         this.doAuxiliaryFaceOffset = true;
         this.axis = 0;
      }

      @Override
      protected void emitQuad(int x, int z, int length, int width, long data) {
         if (RenderDataFactory.VERIFY_MESHING) {
            if (length < 1 || length > 16) {
               throw new IllegalStateException("length out of bounds: " + length);
            }

            if (width < 1 || width > 16) {
               throw new IllegalStateException("width out of bounds: " + width);
            }

            if (x < 0 || x > 31) {
               throw new IllegalStateException("x out of bounds: " + x);
            }

            if (z < 0 || z > 31) {
               throw new IllegalStateException("z out of bounds: " + z);
            }

            if (x - (length - 1) < 0 || z - (width - 1) < 0) {
               throw new IllegalStateException("dim out of bounds: " + (x - (length - 1)) + ", " + (z - (width - 1)));
            }
         }

         RenderDataFactory.this.quadCount++;
         x -= length - 1;
         z -= width - 1;
         if (this.axis == 2) {
            int tmp = x;
            x = z;
            z = tmp;
            tmp = length;
            length = width;
            width = tmp;
         }

         int auxData = (int)(data & 67108863L);
         data &= -67108864L;
         int axisSide = auxData & 1;
         int type = auxData >> 1 & 3;
         if (RenderDataFactory.VERIFY_MESHING && type == 3) {
            throw new IllegalStateException();
         } else {
            int auxPos = this.auxiliaryPosition;
            auxPos += 1 - (this.doAuxiliaryFaceOffset ? axisSide : 1);
            if (RenderDataFactory.VERIFY_MESHING && auxPos > 31) {
               throw new IllegalStateException("OOB face: " + auxPos + ", " + axisSide);
            } else {
               int axis = this.axis;
               int face = axis << 1 | axisSide;
               int encodedPosition = face | width - 1 << 7 | length - 1 << 3;
               encodedPosition |= x << (axis == 2 ? 16 : 21);
               encodedPosition |= z << (axis == 1 ? 16 : 11);
               int shiftAmount = axis == 0 ? 16 : (axis == 1 ? 11 : 21);
               encodedPosition |= auxPos << shiftAmount;
               long quad = data | Integer.toUnsignedLong(encodedPosition);
               int bufferIdx = type + (type == 2 ? face : 0);
               long bufferOffset = RenderDataFactory.this.quadCounters[bufferIdx]++ * 8L + bufferIdx * 8L * 65536L;
               MemoryUtil.memPutLong(RenderDataFactory.this.quadBufferPtr + bufferOffset, quad);
               if (axis == 0) {
                  RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, auxPos);
                  RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, auxPos);
                  RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, x);
                  RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, x + length);
                  RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, z);
                  RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, z + width);
               } else if (axis == 1) {
                  RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, auxPos);
                  RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, auxPos);
                  RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, x);
                  RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, x + length);
                  RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, z);
                  RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, z + width);
               } else {
                  RenderDataFactory.this.minX = Math.min(RenderDataFactory.this.minX, auxPos);
                  RenderDataFactory.this.maxX = Math.max(RenderDataFactory.this.maxX, auxPos);
                  RenderDataFactory.this.minY = Math.min(RenderDataFactory.this.minY, x);
                  RenderDataFactory.this.maxY = Math.max(RenderDataFactory.this.maxY, x + length);
                  RenderDataFactory.this.minZ = Math.min(RenderDataFactory.this.minZ, z);
                  RenderDataFactory.this.maxZ = Math.max(RenderDataFactory.this.maxZ, z + width);
               }
            }
         }
      }
   }
}
