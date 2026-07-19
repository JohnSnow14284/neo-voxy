package me.cortex.voxy.commonImpl.mixin.sable;

import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import me.cortex.voxy.commonImpl.compat.sable.SableLodChunkManager;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk")
public class MixinSubLevelHoldingChunk {
    @Inject(method = "canLoadSubLevel", at = @At("HEAD"), cancellable = true, remap = false)
    private static void voxy$skipAlreadyActiveSubLevel(ServerLevel level, SubLevelData data, CallbackInfoReturnable<Boolean> cir) {
        if (SableLodChunkManager.isSubLevelAlreadyActive(level, data)) {
            cir.setReturnValue(false);
        }
    }
}
