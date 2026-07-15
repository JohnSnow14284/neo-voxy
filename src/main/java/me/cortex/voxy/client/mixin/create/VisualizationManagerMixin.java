package me.cortex.voxy.client.mixin.create;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import me.cortex.voxy.compat.far.create.FarContraptionRenderContext;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VisualizationManager.class, remap = false)
public interface VisualizationManagerMixin {
    @Inject(method = "supportsVisualization", at = @At("HEAD"), cancellable = true, remap = false)
    private static void voxy$forceFallbackForDetachedContraptions(LevelAccessor level,
                                                                  CallbackInfoReturnable<Boolean> cir) {
        if (FarContraptionRenderContext.forceFallback()) {
            cir.setReturnValue(false);
        }
    }
}
