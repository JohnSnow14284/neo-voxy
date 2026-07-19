package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.trains.bogey.BogeyVisual;
import com.simibubi.create.content.trains.entity.CarriageContraptionVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//Moving trains render through this Flywheel entity visual (GPU instances inside a VisualEmbedding),
//the same instanced path as the track (see MixinTrackVisual) and equally distance-cull-free: once
//the carriage entity is tracked its whole contraption is drawn regardless of vanilla view distance,
//so over voxy LOD (where EntityCulling finds no real occluder) it floats on top of everything.
//
//The visual is already a DynamicVisual; inject its per-frame beginFrame and, beyond the effective
//render distance (3D spherical, honoring height), collapse the entire embedding to a zero matrix.
//Every vertex maps to one point, so every triangle degenerates to zero area and rasterizes to no
//pixels - the whole contraption (structure, child BEs, actors, bogeys, all drawn through this
//embedding) vanishes without deleting or recreating anything. Cancelling beginFrame skips the
//normal transform update that would otherwise restore the real position while we are beyond.
//embedding/entity live on superclasses, so they come through accessors, not inherited @Shadow.
@Mixin(CarriageContraptionVisual.class)
public abstract class MixinCarriageContraptionVisual {
    //The carriage's own field: the bogey visuals. Created from the main VisualizationContext
    //(world-space instancers), not the embedding, so zeroing the embedding pose hides the carriage
    //body but leaves the bogeys floating. BogeyVisual.hide() collapses their instances directly.
    @Shadow @org.spongepowered.asm.mixin.Final private BogeyVisual[] visuals;

    private static final Matrix4f VOXY$ZERO_POSE = new Matrix4f().scaling(0.0f);
    private static final Matrix3f VOXY$ZERO_NORMAL = new Matrix3f().scaling(0.0f);

    //TAIL, not HEAD+cancel: the engine flushes every tracked embedding's pose to the GPU each frame
    //(EnvironmentStorage.flush iterates all, removing only deleted ones), and beginFrame is where the
    //embedding gets its per-frame setup (matrix-index allocation, child/actor instance updates). A
    //HEAD cancel skipped that setup, so the embedding either fell out of the flush set or its GPU
    //matrix index went stale and the carriage kept drawing at last frame's real pose. Instead we let
    //beginFrame run in full, then at the tail overwrite the embedding pose with a zero matrix when
    //beyond the render distance - flush then uploads zero, collapsing every vertex to a point (no
    //pixels). Next frame within range, beginFrame's own setEmbeddingMatrices restores the real pose.
    @Inject(method = "beginFrame", at = @At("TAIL"))
    private void voxy$cullBeyondRenderDistance(DynamicVisual.Context ctx, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            return;
        }
        VisualEmbedding embedding = ((AccessorContraptionVisual) this).voxy$getEmbedding();
        if (embedding == null) {
            return;
        }
        Entity entity = ((AccessorAbstractEntityVisual) this).voxy$getEntity();
        //Riding a sable ship the entity sits at plot-grid coordinates, where a world-space distance is
        //meaningless - leave it to sable
        if (me.cortex.voxy.client.compat.ShipBorne.isShipBorne(entity.getX(), entity.getZ())) {
            return;
        }
        Vec3 cam = ctx.camera().getPosition();
        //Yield exactly where the distant train mesh takes over (not the render distance): any gap
        //between the two thresholds is a ring where both draw, and the pose lag between them shows
        if (me.cortex.voxy.client.compat.create.TrainHandover.beyondLive(entity.position(), cam)) {
            //Carriage body (structure + child BEs + actors) draws through the embedding - collapse it
            embedding.transforms(VOXY$ZERO_POSE, VOXY$ZERO_NORMAL);
            //Bogeys draw through the main context, not the embedding - hide them explicitly. This
            //overrides the update() beginFrame just did; next in-range frame it updates them back.
            for (BogeyVisual bogey : this.visuals) {
                if (bogey != null) {
                    bogey.hide();
                }
            }
        }
    }
}
