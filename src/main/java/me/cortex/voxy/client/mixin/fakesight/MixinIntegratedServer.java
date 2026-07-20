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
        // Keep the radius stable while travelling, but never drive the local server at the protocol
        // ceiling. A 127-chunk radius is roughly 65k chunks; ticket updates then fall behind the
        // player and loading terrain waits for an impractical initial region. 32 is vanilla's
        // practical maximum and still leaves a wide ring for Voxy ingestion.
        int safeRequestDistance = Math.min(
                VoxyConfig.CONFIG.getRequestDistance(),
                VoxyConfig.MAX_INTEGRATED_REQUEST_DISTANCE);
        return Math.max(originalDistance, safeRequestDistance);
    }
}
