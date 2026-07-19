package me.cortex.voxy.client.mixin.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import me.cortex.voxy.client.compat.create.KineticCull;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//Distance cull at the shared entry of every Create-style block-entity renderer. Addons override
//renderSafe with their own animation passes (bits_n_bobs' flywheel bearing spins in its BER, no
//Flywheel visual involved), so a cull on the base renderSafe never fires for them - but they all come
//through this final render(). Kinetic block entities beyond the render distance skip straight to the
//snapshot copy; everything else is untouched.
@Mixin(SafeBlockEntityRenderer.class)
public class MixinSafeBlockEntityRenderer {
    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"), cancellable = true)
    private void voxy$cullBeyondRenderDistance(BlockEntity be, float partialTicks, PoseStack ms,
                                               MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        //The snapshot capture drives this very entry point - never cancel its own pass
        if (me.cortex.voxy.client.compat.create.KineticSnapshots.isCapturingOnThisThread()) {
            return;
        }
        //Only cull world-placed block entities: a contraption's virtual BEs carry contraption-LOCAL
        //positions (and a virtual level), which read as always-beyond and vanished from every moving
        //structure's interior
        if (be instanceof KineticBlockEntity
                && be.getLevel() == net.minecraft.client.Minecraft.getInstance().level
                && KineticCull.beyondForRender(be.getBlockPos())) {
            ci.cancel();
        }
    }
}
