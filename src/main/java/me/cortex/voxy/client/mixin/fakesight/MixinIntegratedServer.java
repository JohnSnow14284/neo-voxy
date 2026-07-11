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
            index = 1
    )
    private int voxy$modifyIntegratedServerRenderDistance(int originalDistance) {
        if (VoxyConfig.CONFIG.enableExtendedRequestDistance && VoxyConfig.CONFIG.isRenderingEnabled()) {
            return VoxyConfig.CONFIG.getRequestDistance();
        }
        return originalDistance;
    }
}
