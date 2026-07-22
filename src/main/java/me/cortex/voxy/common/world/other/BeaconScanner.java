package me.cortex.voxy.common.world.other;

import me.cortex.voxy.common.Logger;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;

//Finds the beacons in a section as it is ingested. The palette answers "could this section contain one"
//over a handful of entries, so a section of stone is rejected without touching a single block, and only
//the rare hit pays for the 4096-block walk.
public final class BeaconScanner {
    private BeaconScanner() {}

    public static void scan(BeaconIndex index, LevelChunkSection section, int sx, int sy, int sz) {
        try {
            if (!section.maybeHas(state -> state.is(Blocks.BEACON))) {
                //Still reported: the section may have held one until this ingest, and the index is keyed
                //per section precisely so that "nothing here" is a meaningful answer.
                index.setSection(sx, sy, sz, null);
                return;
            }

            short[] found = null;
            int count = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (!section.getBlockState(x, y, z).is(Blocks.BEACON)) {
                            continue;
                        }
                        if (found == null) {
                            found = new short[8];
                        } else if (count == found.length) {
                            //A section packed edge to edge with beacons is 4096, but the count is stored
                            //in a byte and nothing sane approaches either number
                            if (count == 255) {
                                Logger.warn("Section " + sx + "," + sy + "," + sz + " holds more than 255 beacons; indexing the first 255");
                                y = 16; z = 16; break;
                            }
                            var grown = new short[Math.min(found.length * 2, 255)];
                            System.arraycopy(found, 0, grown, 0, count);
                            found = grown;
                        }
                        found[count++] = BeaconIndex.packLocal(x, y, z);
                    }
                }
            }

            if (count == 0) {
                index.setSection(sx, sy, sz, null);
                return;
            }
            var exact = new short[count];
            System.arraycopy(found, 0, exact, 0, count);
            index.setSection(sx, sy, sz, exact);
        } catch (Throwable t) {
            //Ingest runs third-party block code and holds a world ref; losing the index for one section is
            //not worth taking the section, or the ref, with it
            Logger.error("Scanning section " + sx + "," + sy + "," + sz + " for beacons", t);
        }
    }
}
