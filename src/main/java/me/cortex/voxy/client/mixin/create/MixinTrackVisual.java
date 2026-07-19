package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.trains.track.TrackVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.SectionTrackedVisual.SectionCollector;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

//Create's track bezier is drawn by this Flywheel visual (GPU instances), not the vanilla BER - so
//getViewDistance/renderSafe clamps never touch it, and under iris+colorwheel (Flywheel forced on)
//it is the only draw path. The visual has no distance culling of its own: once the BE's chunk is
//client-loaded it submits the whole span as instances, and EntityCulling (nowheel) only occlusion-
//culls it - which fails over voxy LOD where there is no real block to occlude, leaving it floating.
//
//Make the visual a SimpleDynamicVisual so Flywheel calls beginFrame every frame (engine-agnostic:
//both the default engine and colorwheel's ClrwlEngine honor DynamicVisual.planFrame), and drop the
//instances beyond the effective render distance (3D spherical, honoring height), rebuilding them on
//return - a view distance for the instanced path.
@Mixin(TrackVisual.class)
public abstract class MixinTrackVisual implements SimpleDynamicVisual {
    @Shadow @org.spongepowered.asm.mixin.Final protected BlockPos pos;
    @Shadow protected SectionCollector lightSections;
    @Shadow public abstract void _delete();
    @Shadow public abstract LongSet collectLightSections();

    @Shadow private void collectConnections() { throw new AssertionError(); }

    private boolean voxy$culled;

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        //Track sitting on a sable ship is at plot-grid coordinates, where a world-space distance is
        //meaningless - leave it to sable
        if (me.cortex.voxy.client.compat.ShipBorne.isShipBorne(this.pos)) {
            return;
        }
        boolean rendering = VoxyConfig.CONFIG.isRenderingEnabled();
        Vec3 cam = ctx.camera().getPosition();
        double reach = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        double dx = this.pos.getX() + 0.5 - cam.x;
        double dy = this.pos.getY() + 0.5 - cam.y;
        double dz = this.pos.getZ() + 0.5 - cam.z;
        boolean beyond = rendering && (dx * dx + dy * dy + dz * dz) > reach * reach;

        if (beyond) {
            //Idempotent: also clears instances a BE update may have rebuilt while we were far
            this._delete();
            this.voxy$culled = true;
        } else if (this.voxy$culled) {
            this.collectConnections();
            if (this.lightSections != null) {
                this.lightSections.sections(this.collectLightSections());
            }
            this.voxy$culled = false;
        }
    }
}
