package me.cortex.voxy.client.compat.create;

import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

//EntityCulling (nowheel's base) hides an occlusion-culled entity without touching the level's entity
//list - the entity is "there" but draws nothing. A contraption in that state must NOT make the
//snapshot yield, or the structure blinks out whenever the ray test flips at the transition band.
//The Cullable types live only in the method below, so a pack without EntityCulling never links them.
public final class NowheelCulled {
    private static final boolean PRESENT = ModList.get() != null && ModList.get().isLoaded("entityculling");
    private static boolean unavailable;

    private NowheelCulled() {}

    public static boolean isCulled(Entity entity) {
        if (!PRESENT || unavailable) {
            return false;
        }
        try {
            return entity instanceof dev.tr7zw.entityculling.versionless.access.Cullable cullable
                    && cullable.isCulled();
        } catch (LinkageError e) {
            unavailable = true;
            return false;
        }
    }

    //EntityCulling's async ray test runs against the entity's TRUE position - for ship-borne content
    //that is the plot grid ~2e7 blocks out, so the ray always reports occluded and EC cancels the
    //renderEntity call outright (a layer BELOW shouldRender, where our gate exemptions can't reach).
    //setCulled(false) is EC's own escape hatch: it clears the flag AND arms the 1s forced-visible
    //timeout, which its render hook checks before everything else. Called every frame from the
    //dispatcher gate, so the async task flipping the flag back can never win a full frame.
    public static void uncull(Entity entity) {
        if (!PRESENT || unavailable) {
            return;
        }
        try {
            if (entity instanceof dev.tr7zw.entityculling.versionless.access.Cullable cullable) {
                cullable.setCulled(false);
            }
        } catch (LinkageError e) {
            unavailable = true;
        }
    }
}
