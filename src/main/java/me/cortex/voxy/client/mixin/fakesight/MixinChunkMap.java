package me.cortex.voxy.client.mixin.fakesight;

import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ChunkMap.class)
public abstract class MixinChunkMap {
    @ModifyArg(
            method = "setServerViewDistance",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/Mth;clamp(III)I"
            ),
            index = 2,
            require = 0
    )
    private int voxy$extendIntegratedServerLimit(int vanillaLimit) {
        if (VoxyConfig.CONFIG.enableExtendedRequestDistance
                && VoxyConfig.CONFIG.isRenderingEnabled()) {
            // The internal tracking radius is one chunk larger than the
            // user-facing request distance.
            return Math.max(vanillaLimit, VoxyConfig.MAX_REQUEST_DISTANCE + 1);
        }
        return vanillaLimit;
    }
}
