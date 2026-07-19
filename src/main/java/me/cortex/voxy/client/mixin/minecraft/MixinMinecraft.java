package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ClientSessionEvents;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    @Inject(method = "clearClientLevel", at = @At("HEAD"), require = 0)
    private void voxy$beforeClientLevelIsDiscarded(CallbackInfo ci) {
        ClientSessionEvents.sessionEnd();
    }

    @Inject(method = "disconnect", at = @At("TAIL"), require = 0)
    private void voxy$afterDisconnect(CallbackInfo ci) {
        ClientSessionEvents.sessionEnd();
    }
}
