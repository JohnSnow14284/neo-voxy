package me.cortex.voxy.client.mixin.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//Contraptions render through their vanilla EntityRenderer even under Flywheel/colorwheel: Create
//registers the contraption entity visual with renderNormally=true, so Flywheel's entity-skip
//(skipVanillaRender) is false and it does not cancel renderEntity. ContraptionEntityRenderer.render
//therefore runs every frame and draws passes that bypass the Flywheel VisualEmbedding that
//MixinCarriageContraptionVisual zeroes:
//  - renderBlockEntities: contraption BEs with no Flywheel visualizer.
//  - renderActors -> MovementBehaviour.renderInContraption: the "changes form on a train" actors -
//    create:blaze_burner (disableBlockEntityRendering, drawn via its renderer) and create:controls
//    (no BlockEntity at all). Neither has an ActorVisual, so they live outside the embedding and
//    ignore the vanilla view distance, floating over LOD past the render distance.
//Cancel the whole render beyond the effective render distance (3D spherical, honoring height) using
//the same entity.position() anchor as the carriage-body cull, so body and actors vanish together.
//Covers every contraption type on both backends; placed blocks never enter this renderer. This is a
//sibling of MixinCarriageContraptionVisual, not a replacement - that hides the Flywheel embedding +
//bogeys, this cancels the disjoint vanilla pass; both are needed to hide the whole train.
@Mixin(ContraptionEntityRenderer.class)
public class MixinContraptionEntityRenderer {
    @Inject(
            method = "render(Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), cancellable = true)
    private void voxy$cullBeyondRenderDistance(AbstractContraptionEntity entity, float yaw, float partialTicks,
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
        //Carriages hand over to the distant train mesh at the shared handover boundary; the ring
        //between it and the render distance is where the pose lag between live actors and the
        //streamed distant body showed consoles/burners floating off the train
        if (entity instanceof com.simibubi.create.content.trains.entity.CarriageContraptionEntity) {
            if (me.cortex.voxy.client.compat.create.TrainHandover.beyondLive(entity.position(), cam)) {
                ci.cancel();
            }
            return;
        }
        double reach = mc.options.getEffectiveRenderDistance() * 16.0;
        if (entity.position().distanceToSqr(cam) > reach * reach) {
            ci.cancel();
        }
    }
}
