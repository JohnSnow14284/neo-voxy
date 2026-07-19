package me.cortex.voxy.client.mixin.eclipticseasons;

import me.cortex.voxy.client.core.compat.eclipticseasons.VoxyTool;
import java.util.function.BooleanSupplier;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={ClientLevel.class})
public abstract class MixinClientLevel {
    @Inject(at={@At(value="HEAD")}, method={"tick"})
    public void eclipticseasons$tick_refresh_voxy(BooleanSupplier pHasTimeLeft, CallbackInfo ci) {
        VoxyTool.tryUpdate();
    }
}

