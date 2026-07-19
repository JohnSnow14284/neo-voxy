package me.cortex.voxy.client.mixin.fakesight;

import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Options;
import net.minecraft.server.level.ClientInformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Options.class)
public abstract class MixinOptions {
    @Inject(
            method = "buildPlayerInformation",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private void voxy$modifyPlayerInformationRenderDistance(CallbackInfoReturnable<ClientInformation> cir) {
        if (net.neoforged.neoforge.client.loading.ClientModLoader.isLoading()
                || !VoxyConfig.CONFIG.enableExtendedRequestDistance
                || !VoxyConfig.CONFIG.isRenderingEnabled()) return;

        ClientInformation current = cir.getReturnValue();
        if (current == null || current.viewDistance() == VoxyConfig.CONFIG.getRequestDistance()) return;
        cir.setReturnValue(new ClientInformation(
                current.language(), VoxyConfig.CONFIG.getRequestDistance(), current.chatVisibility(),
                current.chatColors(), current.modelCustomisation(), current.mainHand(),
                current.textFilteringEnabled(), current.allowsListing()));
    }
}
