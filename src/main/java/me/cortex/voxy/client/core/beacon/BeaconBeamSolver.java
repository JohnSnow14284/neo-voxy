package me.cortex.voxy.client.core.beacon;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.util.FastColor;
import net.minecraft.world.level.block.BeaconBeamBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

//Works out what a beacon's beam looks like from the voxel store rather than from a BlockEntity, so a
//beacon thousands of blocks away - whose chunk is not loaded and never will be - still has a beam.
//
//The store is the same data the LOD terrain was built from, which is the point: the beam cannot
//disagree with the world drawn around it, and it needs no invalidation when the glass above one
//changes. Whatever the last ingest of those sections saw is what both of them show.
public final class BeaconBeamSolver {
    //Vanilla stops at the build limit; the beam is drawn far past it but the scan has to end somewhere
    private static final int MAX_SCAN_HEIGHT = 1024;

    private BeaconBeamSolver() {}

    //One run of constant colour. Heights are absolute world Y.
    public record Segment(int colorRgb, int yBottom, int yTop) {}

    public static List<Segment> solve(WorldEngine engine, int bx, int by, int bz) {
        //A beacon with no base emits nothing. The gate is in BeaconBlockEntity.getBeamSections, which
        //returns an empty list while levels == 0 - the segments are still computed and stored, they are
        //just never handed out, so neither the vanilla renderer nor Quark's replacement draws them.
        //tick() alone reads as though there were no such gate.
        if (!hasBase(engine, bx, by, bz)) {
            return List.of();
        }
        var segments = new ArrayList<Segment>();
        int currentColor = 0xFFFFFF;
        int segmentBottom = by + 1;
        boolean anyColorSeen = false;

        var mapper = engine.getMapper();
        //Not clamped to the build height: vanilla's own last beam segment runs to 1024, and sections
        //above the world are the cheapest possible acquire - the backend misses, nothing is deserialised,
        //and no voxel is read.
        int top = by + MAX_SCAN_HEIGHT;

        //One acquire per section rather than per block: the column walks 16 blocks of a section before
        //it needs the next one, and acquire/release is the expensive part
        int y = by + 1;
        while (y <= top) {
            int sectionY = y >> 5;
            var section = engine.acquireIfExists(0, bx >> 5, sectionY, bz >> 5);
            if (section == null) {
                //Nothing ingested up here. Above the surface that is the normal case and the beam simply
                //continues; the alternative - stopping - would cut every beam at the last stored section.
                y = (sectionY + 1) << 5;
                continue;
            }
            try {
                int lx = bx & 31, lz = bz & 31;
                while (y <= top && (y >> 5) == sectionY) {
                    long voxel = section.get(lx | (lz << 5) | ((y & 31) << 10));
                    if (voxel != 0 && !Mapper.isAir(voxel)) {
                        int blockId = Mapper.getBlockId(voxel);
                        BlockState state;
                        try {
                            state = mapper.getBlockStateFromBlockId(blockId);
                        } catch (Exception e) {
                            state = null;
                        }
                        if (state != null && isRedirector(state)) {
                            //Quark's Beacon Redirection turns the beam at a corundum cluster, so it stops
                            //being a vertical column and this solver cannot describe it. Drawing the
                            //straight beam anyway would put a beam through terrain the real one turns
                            //away from - worse than drawing none until redirection is implemented.
                            return List.of();
                        }
                        Integer tint = state == null ? null : tintOf(state);
                        if (tint != null) {
                            //Vanilla's rule: the first coloured block replaces white outright, and only
                            //later changes average into what came before
                            int next = anyColorSeen ? FastColor.ARGB32.average(currentColor, tint) : tint;
                            anyColorSeen = true;
                            if (next != currentColor) {
                                if (y > segmentBottom) {
                                    segments.add(new Segment(currentColor, segmentBottom, y));
                                }
                                currentColor = next;
                                segmentBottom = y;
                            }
                        } else if (state != null && isBeamStopper(mapper, blockId, state)) {
                            //Vanilla clears checkingBeamSections here rather than keeping what it has,
                            //so an obstructed beacon shows no beam at all rather than one cut off at the
                            //ceiling. A beacon under a roof is the ordinary case.
                            return List.of();
                        }
                    }
                    y++;
                }
            } finally {
                section.release();
            }
        }

        if (top > segmentBottom) {
            segments.add(new Segment(currentColor, segmentBottom, top));
        }
        return segments;
    }

    //Only the first pyramid layer, because only levels != 0 matters here - the higher layers change the
    //powers on offer, not whether there is a beam. Vanilla's updateBase walks 3x3 up to 9x9 for the same
    //first answer.
    private static boolean hasBase(WorldEngine engine, int bx, int by, int bz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!isBaseBlock(engine, bx + dx, by - 1, bz + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isBaseBlock(WorldEngine engine, int x, int y, int z) {
        var section = engine.acquireIfExists(0, x >> 5, y >> 5, z >> 5);
        if (section == null) {
            //The layer under the beacon was never ingested, so there is nothing to justify a beam with
            return false;
        }
        try {
            long voxel = section.get((x & 31) | ((z & 31) << 5) | ((y & 31) << 10));
            if (voxel == 0 || Mapper.isAir(voxel)) {
                return false;
            }
            return engine.getMapper().getBlockStateFromBlockId(Mapper.getBlockId(voxel))
                    .is(net.minecraft.tags.BlockTags.BEACON_BASE_BLOCKS);
        } catch (Exception e) {
            return false;
        } finally {
            section.release();
        }
    }

    //The vanilla hook for "this block tints a beacon beam" - stained glass and panes implement it, and so
    //do modded blocks that opt in, without any of them needing a Level to ask
    private static Integer tintOf(BlockState state) {
        if (state.getBlock() instanceof BeaconBeamBlock beam) {
            return beam.getColor().getTextureDiffuseColor();
        }
        return null;
    }

    //What Quark turns a beam on: corundum clusters when its Corundum module is on, amethyst otherwise.
    //Matched by registry name so this needs no compile-time dependency on Quark, and costs nothing in a
    //game without it - the amethyst check answers first for every block that is not a cluster.
    private static boolean isRedirector(BlockState state) {
        if (state.is(net.minecraft.world.level.block.Blocks.AMETHYST_CLUSTER)) {
            return true;
        }
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key != null && key.getPath().endsWith("corundum_cluster");
    }

    //Vanilla kills the beam on anything that blocks all light, bedrock excepted
    private static boolean isBeamStopper(Mapper mapper, int blockId, BlockState state) {
        if (state.is(Blocks.BEDROCK)) {
            return false;
        }
        return mapper.getBlockStateOpacity(blockId) >= 15;
    }
}
