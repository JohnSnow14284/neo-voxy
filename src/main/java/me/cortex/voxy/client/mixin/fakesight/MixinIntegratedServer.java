package me.cortex.voxy.client.mixin.fakesight;

import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(IntegratedServer.class)
public abstract class MixinIntegratedServer {
    @ModifyArg(
            method = "tickServer",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Math;max(II)I",
                    ordinal = 0
            ),
            index = 1,
            require = 0
    )
    private int voxy$modifyIntegratedServerRenderDistance(int originalDistance) {
        if (!VoxyConfig.CONFIG.enableExtendedRequestDistance
                || !VoxyConfig.CONFIG.isRenderingEnabled()) {
            return originalDistance;
        }
        // Keep the actual server view radius at the configured target. The previous moving/stationary
        // radius oscillation repeatedly unloaded the outer cache and could therefore never accumulate
        // data at the selected distance while travelling.
        return Math.max(originalDistance, VoxyConfig.CONFIG.getRequestDistance());
    }
}
