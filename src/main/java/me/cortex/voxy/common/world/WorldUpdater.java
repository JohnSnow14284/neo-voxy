package me.cortex.voxy.common.world;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;

public class WorldUpdater {
   public static void insertUpdate(WorldEngine into, VoxelizedSection section) {
      if (!into.isLive) {
         throw new IllegalStateException("World is not live");
      } else {
         boolean shouldCheckEmptiness = false;
         WorldSection previousSection = null;

         for (int lvl = 0; lvl <= 4; lvl++) {
            WorldSection worldSection = into.acquire(lvl, section.x >> lvl + 1, section.y >> lvl + 1, section.z >> lvl + 1);
            int emptinessStateChange = 0;
            if (lvl != 0 && shouldCheckEmptiness) {
               emptinessStateChange = worldSection.updateEmptyChildState(previousSection);
               previousSection.release();
               previousSection = null;
            }

            long status = insertSectionLvlIntoWorld(section, worldSection);
            boolean didStateChange = (status & 1L) == 1L;
            int airCount = (int)(status >> 1 & 8191L);
            if (lvl == 0) {
               int nonAirCountDelta = section.lvl0NonAirCount - (4096 - airCount);
               if (nonAirCountDelta != 0) {
                  worldSection.addNonEmptyBlockCount(nonAirCountDelta);
                  emptinessStateChange = worldSection.updateLvl0State() ? 2 : 0;
               }
            }

            if (didStateChange || emptinessStateChange != 0) {
               int neighbors = 0;
               if (didStateChange) {
                  neighbors |= (section.y ^ section.y - 1) >> lvl + 1 == 0 ? 0 : 1;
                  neighbors |= (section.y ^ section.y + 1) >> lvl + 1 == 0 ? 0 : 2;
                  neighbors |= (section.x ^ section.x - 1) >> lvl + 1 == 0 ? 0 : 4;
                  neighbors |= (section.x ^ section.x + 1) >> lvl + 1 == 0 ? 0 : 8;
                  neighbors |= (section.z ^ section.z - 1) >> lvl + 1 == 0 ? 0 : 16;
                  neighbors |= (section.z ^ section.z + 1) >> lvl + 1 == 0 ? 0 : 32;
               }

               into.markDirty(worldSection, (didStateChange ? 1 : 0) | (emptinessStateChange != 0 ? 2 : 0), neighbors);
            }

            if (!didStateChange && emptinessStateChange != 2) {
               worldSection.release();
               break;
            }

            if (emptinessStateChange == 2) {
               shouldCheckEmptiness = true;
               previousSection = worldSection;
            } else {
               shouldCheckEmptiness = false;
               previousSection = null;
               worldSection.release();
            }
         }

         if (previousSection != null) {
            previousSection.release();
         }
      }
   }

   private static long insertSectionLvlIntoWorld(VoxelizedSection section, WorldSection worldSection) {
      long[] vdat = section.section;
      int lvl = worldSection.lvl;
      int msk = (1 << lvl + 1) - 1;
      int bx = (section.x & msk) << 4 - lvl;
      int by = (section.y & msk) << 4 - lvl;
      int bz = (section.z & msk) << 4 - lvl;
      int airCount = 0;
      boolean didStateChange = false;
      long[] secD = worldSection.data;
      int baseSec = bx | bz << 5 | by << 10;
      if (lvl == 0) {
         int secMsk = 15852;
         int iSecMsk1 = -15852;
         int secIdx = 0;

         for (int i = 0; i <= 4095; i += 4) {
            int cSecIdx = secIdx + baseSec;
            secIdx = secIdx + -15852 & 15852;
            long oldId0 = secD[cSecIdx + 0];
            secD[cSecIdx + 0] = vdat[i + 0];
            long oldId1 = secD[cSecIdx + 1];
            secD[cSecIdx + 1] = vdat[i + 1];
            long oldId2 = secD[cSecIdx + 2];
            secD[cSecIdx + 2] = vdat[i + 2];
            long oldId3 = secD[cSecIdx + 3];
            secD[cSecIdx + 3] = vdat[i + 3];
            airCount += Mapper.isAir(oldId0) ? 1 : 0;
            didStateChange |= vdat[i + 0] != oldId0;
            airCount += Mapper.isAir(oldId1) ? 1 : 0;
            didStateChange |= vdat[i + 1] != oldId1;
            airCount += Mapper.isAir(oldId2) ? 1 : 0;
            didStateChange |= vdat[i + 2] != oldId2;
            airCount += Mapper.isAir(oldId3) ? 1 : 0;
            didStateChange |= vdat[i + 3] != oldId3;
         }
      } else {
         int baseVIdx = VoxelizedSection.getBaseIndexForLevel(lvl);
         int secMsk = 15 >> lvl;
         secMsk |= secMsk << 5 | secMsk << 10;
         int iSecMsk1 = ~secMsk + 1;
         int secIdx = 0;

         for (int i = baseVIdx; i <= (4095 >> lvl * 3) + baseVIdx; i++) {
            int cSecIdx = secIdx + baseSec;
            secIdx = secIdx + iSecMsk1 & secMsk;
            long newId = vdat[i];
            long oldId = secD[cSecIdx];
            didStateChange |= newId != oldId;
            secD[cSecIdx] = newId;
         }
      }

      long status = 0L;
      long var32 = status | (didStateChange ? 1L : 0L);
      return var32 | Integer.toUnsignedLong(airCount) << 1;
   }
}
