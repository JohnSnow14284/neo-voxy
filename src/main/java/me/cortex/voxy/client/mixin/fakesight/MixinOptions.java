package me.cortex.voxy.client.mixin.fakesight;

import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Options.class)
public abstract class MixinOptions {
    @Redirect(
            method = "buildPlayerInformation",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;",
                    ordinal = 0
            )
    )
    private Object voxy$modifyPlayerInformationRenderDistance(OptionInstance<?> instance) {
        if (VoxyConfig.CONFIG.enableExtendedRequestDistance && VoxyConfig.CONFIG.isRenderingEnabled()) {
            return Integer.valueOf(VoxyConfig.CONFIG.getRequestDistance());
        }
        return instance.get();
    }
}
