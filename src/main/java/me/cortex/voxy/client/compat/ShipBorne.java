package me.cortex.voxy.client.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.neoforged.fml.ModList;

//Sable keeps a ship's blocks and entities in the main level at plot-grid coordinates (around 2.05e7) and
//only moves them onto the ship when rendering. A world-space distance check therefore reads ~2e7 blocks
//for anything riding a ship, so every distance cull would fire on it and nothing on a ship would ever
//draw. The culls ask here first and leave ship-borne content alone: it is drawn against the ship's own
//geometry rather than floating over the LOD, and sable already tracks and frustum-culls its sub-levels.
//
//The sable types are confined to SableShipContent, which the JVM only links once the call below actually
//runs, so a game without sable never loads them and pays a single static boolean.
public final class ShipBorne {
    private static final boolean SABLE_PRESENT = ModList.get() != null && ModList.get().isLoaded("sable");
    private static boolean unavailable;

    private ShipBorne() {}

    public static boolean isShipBorne(double x, double z) {
        return inSubLevel(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
    }

    public static boolean isShipBorne(BlockPos pos) {
        return inSubLevel(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static boolean anyShipPresent() {
        if (!SABLE_PRESENT || unavailable) {
            return false;
        }
        try {
            return me.cortex.voxy.client.compat.sable.SableShipContent.hasAnyShip();
        } catch (LinkageError | RuntimeException e) {
            unavailable = true;
            return false;
        }
    }

    //Self-heal for sable's join-time-only Flywheel plot registration (see SableShipContent) - safe to
    //call every frame, no-ops once the state exists
    public static void ensureShipFlywheelState(net.minecraft.world.entity.Entity entity) {
        if (!SABLE_PRESENT || unavailable) {
            return;
        }
        try {
            me.cortex.voxy.client.compat.sable.SableShipContent.ensureFlywheelState(entity);
        } catch (LinkageError | RuntimeException e) {
            unavailable = true;
        }
    }

    private static boolean inSubLevel(int chunkX, int chunkZ) {
        if (!SABLE_PRESENT || unavailable) {
            return false;
        }
        try {
            return me.cortex.voxy.client.compat.sable.SableShipContent.inSubLevel(chunkX, chunkZ);
        } catch (LinkageError | RuntimeException e) {
            unavailable = true;
            return false;
        }
    }
}
