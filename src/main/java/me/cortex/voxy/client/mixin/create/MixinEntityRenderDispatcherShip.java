package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import me.cortex.voxy.client.compat.ShipBorne;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//A ship-borne contraption entity sits at plot-grid coordinates (~2e7 blocks out), so the vanilla
//render gate kills it before sable ever gets to relocate it: shouldRender calls
//entity.shouldRenderAtSqrDistance (a size-scaled distance limit the plot offset dwarfs) and then
//frustum-tests the plot-space AABB. Sable's own compat covers its regular entities but not
//contraptions - they simply never render on a ship. Force the gate open for exactly that case; the
//actual draw is still positioned by sable's transform. (Approach validated against SSRD, which
//force-opens the same gates globally for its long-range rendering.)
@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcherShip {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void voxy$forceShipContraptions(E entity, Frustum frustum, double camX, double camY, double camZ,
                                                               CallbackInfoReturnable<Boolean> cir) {
        //getContraption() != null mirrors the precondition Create's own shouldRender override applies -
        //forcing the gate open before the contraption NBT has synced would just run an empty render
        if (entity instanceof AbstractContraptionEntity contraption
                && contraption.getContraption() != null
                && ShipBorne.isShipBorne(entity.getX(), entity.getZ())) {
            //EntityCulling cancels renderEntity a layer below this gate, and its ray test against the
            //plot-grid position always says occluded - clear its flag (arms EC's own forced-visible
            //timeout) so the pass we just opened actually draws
            me.cortex.voxy.client.compat.create.NowheelCulled.uncull(entity);
            cir.setReturnValue(true);
        }
    }
}
