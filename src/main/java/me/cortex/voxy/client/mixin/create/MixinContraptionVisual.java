package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.contraptions.render.ContraptionVisual;
import com.simibubi.create.content.trains.entity.CarriageContraptionVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//Generalises the carriage body cull to every contraption: bearings, gantries, elevators, mounted
///oriented contraptions (airships, boats from addons) all render their body through this base
//ContraptionVisual's VisualEmbedding, the same way a train does. Their vanilla actor/BE pass is
//already covered by MixinContraptionEntityRenderer (base EntityRenderer); this covers the Flywheel
//embedding body. Same mechanism as MixinCarriageContraptionVisual: at beginFrame TAIL (so the
//engine's per-frame embedding setup + setEmbeddingMatrices already ran), collapse the embedding
//pose to a zero matrix beyond the render distance so every vertex degenerates to a point.
//embedding is this class's own field (direct @Shadow); entity comes via the AbstractEntityVisual
//accessor. The train is EXCLUDED here - CarriageContraptionVisual (the one subclass) keeps its own
//validated mixin (embedding + bogey hide), and would otherwise be double-handled since its beginFrame
//calls super.beginFrame.
@Mixin(ContraptionVisual.class)
public abstract class MixinContraptionVisual {
    @Shadow @Final protected VisualEmbedding embedding;

    private static final Matrix4f VOXY$ZERO_POSE = new Matrix4f().scaling(0.0f);
    private static final Matrix3f VOXY$ZERO_NORMAL = new Matrix3f().scaling(0.0f);

    @Inject(method = "beginFrame", at = @At("TAIL"))
    private void voxy$cullBeyondRenderDistance(DynamicVisual.Context ctx, CallbackInfo ci) {
        //Train has its own mixin (fires via super.beginFrame); skip here to avoid double work
        if ((Object) this instanceof CarriageContraptionVisual) {
            return;
        }
        if (!VoxyConfig.CONFIG.isRenderingEnabled() || this.embedding == null) {
            return;
        }
        Entity entity = ((AccessorAbstractEntityVisual) this).voxy$getEntity();
        //Riding a sable ship the entity sits at plot-grid coordinates, where a world-space distance is
        //meaningless - leave it to sable
        if (me.cortex.voxy.client.compat.ShipBorne.isShipBorne(entity.getX(), entity.getZ())) {
            //Sable only registers its per-plot Flywheel state at entity-join time, which is too early on
            //world load (sub-level packets not in yet) - without the state its setEmbeddingMatrices
            //override silently bails and the block body renders at plot coordinates. Re-register here.
            me.cortex.voxy.client.compat.ShipBorne.ensureShipFlywheelState(entity);
            return;
        }
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double reach = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        if (entity.position().distanceToSqr(cam) > reach * reach) {
            this.embedding.transforms(VOXY$ZERO_POSE, VOXY$ZERO_NORMAL);
        }
    }
}
