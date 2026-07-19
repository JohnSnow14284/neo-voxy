package me.cortex.voxy.client.mixin.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import com.simibubi.create.content.trains.station.StationRenderer;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//The station's grey assembly-arrow disc renders through this vanilla BER (shouldRenderOffScreen=true,
//getViewDistance=192, no Flywheel visual), so like the track BEs it floats over voxy LOD past the
//view distance. Unlike the track, the station has no Flywheel path, so renderSafe is always the draw
//entry - a HEAD cancel beyond the effective render distance (3D spherical, honoring height) reliably
//stops it. Only while voxy renders LOD (the state that keeps the section alive); else Create's own.
@Mixin(StationRenderer.class)
public abstract class MixinStationRenderer {
    @Inject(
            method = "renderSafe(Lcom/simibubi/create/content/trains/station/StationBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"), cancellable = true)
    private void voxy$cullBeyondRenderDistance(StationBlockEntity be, float partialTicks, PoseStack poseStack,
                                               MultiBufferSource buffers, int light, int overlay, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) {
            return;
        }
        if (me.cortex.voxy.client.compat.ShipBorne.isShipBorne(be.getBlockPos())) {
            return;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        double reach = mc.options.getEffectiveRenderDistance() * 16.0;
        if (be.getBlockPos().distToCenterSqr(cam.x, cam.y, cam.z) > reach * reach) {
            ci.cancel();
        }
    }
}
