package me.cortex.voxy.client.mixin.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntityRenderer;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//Twin of MixinContraptionEntityRenderer for the one subclass that draws after super.render:
//CarriageContraptionEntityRenderer.render calls super.render (which MixinContraptionEntityRenderer
//HEAD-cancels) and then, when Flywheel visualization is unsupported (backend OFF), draws the bogeys
//itself. Cancelling the super method only returns to this subclass, whose post-super bogey draw then
//runs - so with the Flywheel backend off (a plain-Create possibility: user command or a GPU without
//instancing) the wheels float over voxy LOD with no carriage body. Backend ON this path is
//supportsVisualization-gated off and bogeys are hidden by MixinCarriageContraptionVisual, so this
//HEAD-cancel is a no-op there beyond preempting the base mixin (identical net effect). Same anchor
//and threshold as every other train cull, so body + bogeys vanish together.
@Mixin(CarriageContraptionEntityRenderer.class)
public class MixinCarriageContraptionEntityRenderer {
    @Inject(
            method = "render(Lcom/simibubi/create/content/trains/entity/CarriageContraptionEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true)
    private void voxy$cullBeyondRenderDistance(CarriageContraptionEntity entity, float yaw, float partialTicks,
                                               PoseStack poseStack, MultiBufferSource buffers, int light,
                                               CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) {
            return;
        }
        if (me.cortex.voxy.client.compat.ShipBorne.isShipBorne(entity.getX(), entity.getZ())) {
            return;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        //Same handover boundary as the distant train mesh - see TrainHandover
        if (me.cortex.voxy.client.compat.create.TrainHandover.beyondLive(entity.position(), cam)) {
            ci.cancel();
        }
    }
}
