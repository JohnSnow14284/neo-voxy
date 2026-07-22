package me.cortex.voxy.commonImpl.mixin.sable;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import me.cortex.voxy.commonImpl.compat.sable.ShipBorneServer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

//A ship-borne contraption entity lives at plot coordinates, and sable already patches vanilla entity
//tracking's other two gates for it (distance measured to the ship's world position, isChunkTracked
//answered from the ship's tracking list). The min(entityRange, viewDistance) clamp is the one left
//standing: past the entity view distance the server untracks the contraption, the client entity
//disappears, and the structure vanishes off the (still rendering) distant hull. Lift the clamp to the
//ship's own sable tracking range so structure and hull sync over exactly the same distance.
//Gated on create+sable by CommonVoxyMixinPlugin.
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class MixinChunkMapTrackedEntityShip {
    @Shadow @Final Entity entity;

    //require = 0: this redirects a vanilla Math.min inside updatePlayer, a method several server
    //optimisation mods rewrite (servercore and friends are on this class in the wild). If one of them
    //gets there first the call site is gone and the injection finds nothing - which as a hard failure
    //takes the whole server down at entity-tracking time. Losing it only means ship-borne contraptions
    //fall back to the vanilla view-distance clamp, so degrade instead of crashing.
    @Redirect(method = "updatePlayer", at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"), require = 0)
    private int voxy$shipContraptionTrackingRange(int range, int viewDistanceBlocks) {
        if (this.entity instanceof AbstractContraptionEntity) {
            int shipRange = ShipBorneServer.shipTrackingRangeBlocks(this.entity);
            if (shipRange > 0) {
                return Math.max(shipRange, Math.min(range, viewDistanceBlocks));
            }
        }
        return Math.min(range, viewDistanceBlocks);
    }
}
