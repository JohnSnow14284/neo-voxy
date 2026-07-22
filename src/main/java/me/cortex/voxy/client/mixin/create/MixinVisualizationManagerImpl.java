package me.cortex.voxy.client.mixin.create;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import me.cortex.voxy.client.compat.ShipBorne;
import me.cortex.voxy.client.compat.sable.VoxySableDepthShim;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.minecraft.world.level.LevelAccessor;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//Create's kinetic BERs early-return while the Flywheel backend runs (supportsVisualization gate at the
//head of every renderSafe), which starves the generic snapshot capture: the machine-specific moving
//parts (press heads, fan blades, flywheel-bearing gears...) only stream in the full backend-off pass.
//During a capture the gate answers false through this thread-local, scoped strictly to the capture
//call stack. Target is the IMPL class's plain static method - the api-side interface static delegates
//here through FlwApiLink, and injecting the interface static proved unreliable.
@Mixin(value = VisualizationManagerImpl.class, remap = false)
public class MixinVisualizationManagerImpl {
    @Inject(method = "supportsVisualization", at = @At("HEAD"), cancellable = true)
    private static void voxy$bypassDuringCapture(LevelAccessor level, CallbackInfoReturnable<Boolean> cir) {
        if (me.cortex.voxy.client.compat.create.KineticSnapshots.isCapturingOnThisThread()) {
            cir.setReturnValue(false);
        }
    }

    //A ship's kinetics render as live Flywheel visuals in the ship's embedding, but this pass tests only
    //against the vanilla depth buffer - under shader packs that keep LOD depth out of it, distant ships'
    //machine parts shine straight through LOD terrain (the hull doesn't: sable's section layers render
    //inside VoxySableDepthShim, which is exactly the combined vanilla+LOD depth this wrap adds here).
    //World-placed visuals sit well inside the LOD start so the extra depth test never rejects them, and
    //the shim writes back only depth this pass actually changed, keeping LOD depth out of the shader
    //pack's depthtex. Skipped without a ship in the level - three fullscreen blits are not free.
    @org.spongepowered.asm.mixin.Unique
    private boolean voxy$depthWrapped;

    @Inject(method = "render", at = @At("HEAD"))
    private void voxy$beginCombinedDepth(RenderContext context, CallbackInfo ci) {
        //Shader packs only: without one, renderToVanillaDepth already writes LOD depth into the depth
        //buffer this pass tests against, and the wrap's five fullscreen depth passes buy nothing
        this.voxy$depthWrapped = IrisUtil.irisShaderPackEnabled()
                && !IrisUtil.irisShadowActive() && ShipBorne.anyShipPresent();
        if (this.voxy$depthWrapped) {
            //In-place variant: Iris rebinds framebuffers inside this pass, which silently evicted the
            //framebuffer-swap wrap - editing the target's own depth texture survives any rebinding
            VoxySableDepthShim.beginInPlace(new Matrix4f(context.modelView()), new Matrix4f(context.projection()));
        }
    }

    //Paired off the flag rather than re-testing the begin condition, which could flip mid-render and
    //desynchronize the begin/end nesting
    @Inject(method = "render", at = @At("RETURN"))
    private void voxy$endCombinedDepth(RenderContext context, CallbackInfo ci) {
        if (this.voxy$depthWrapped) {
            this.voxy$depthWrapped = false;
            VoxySableDepthShim.endInPlace();
        }
    }
}
