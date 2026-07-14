package me.cortex.voxy.commonImpl.mixin.minecraft;

import me.cortex.voxy.compat.create.CreateFarEntityCompat;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class MixinChunkMapTrackedEntity {
    @Shadow @Final Entity entity;

    @Inject(method = "getEffectiveRange", at = @At("RETURN"), cancellable = true)
    private void voxy$extendCreateEffectiveRange(CallbackInfoReturnable<Integer> cir) {
        int configuredChunks = CreateFarEntityCompat.getMaximumTrackingDistance(this.entity.getType());
        if (configuredChunks > 0) {
            cir.setReturnValue(Math.max(cir.getReturnValue(), configuredChunks * 16));
        }
    }

    @Redirect(
            method = "updatePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkMap;getPlayerViewDistance(Lnet/minecraft/server/level/ServerPlayer;)I"
            )
    )
    private int voxy$allowTrainsOutsideChunkView(ChunkMap chunkMap, ServerPlayer player) {
        int original = ((AccessorChunkMap) chunkMap).voxy$invokeGetPlayerViewDistance(player);
        if (!CreateFarEntityCompat.isCreateDynamicEntity(this.entity.getType())) {
            return original;
        }
        int configured = CreateFarEntityCompat.getPlayerTrackingDistance(player, this.entity.getType());
        return Math.max(original, configured);
    }
}
