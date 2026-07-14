package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.compat.create.CreateFarEntityCompat;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class MixinEntity {
    @Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
    private void voxy$keepCreateDynamicEntitiesVisible(double distanceSquared,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (!VoxyConfig.CONFIG.enableCreateFarEntityRendering) {
            return;
        }
        Entity entity = (Entity) (Object) this;
        if (!CreateFarEntityCompat.isCreateDynamicEntity(entity.getType())) {
            return;
        }
        int distanceChunks = CreateFarEntityCompat.isCreateTrainEntity(entity.getType())
                ? VoxyConfig.CONFIG.getCreateTrainRenderDistance()
                : VoxyConfig.CONFIG.getCreateContraptionRenderDistance();
        if (CreateFarEntityCompat.isWithinDistance(distanceSquared, distanceChunks)) {
            cir.setReturnValue(true);
        }
    }
}
