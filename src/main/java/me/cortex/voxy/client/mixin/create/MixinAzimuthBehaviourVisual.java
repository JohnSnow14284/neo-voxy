package me.cortex.voxy.client.mixin.create;

import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//Registers every azimuth behaviour visual with the cull's behaviour index the moment it is built, so
//the distance/enclosure cull running on the PARENT visual also hides the behaviour's instances (chain
//straps and friends) - they have no frame callback of their own to hook. The walker lambda closes over
//the behaviour's own collectCrumblingInstances; @Pseudo so a pack without azimuth skips silently.
@Pseudo
@Mixin(targets = "com.cake.azimuth.behaviour.extensions.RenderedBehaviourExtension$BehaviourVisual", remap = false)
public abstract class MixinAzimuthBehaviourVisual {
    @Shadow(remap = false) @Final protected AbstractBlockEntityVisual<?> parentVisual;

    @Shadow(remap = false)
    public abstract void collectCrumblingInstances(java.util.function.Consumer<dev.engine_room.flywheel.api.instance.Instance> consumer);

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void voxy$register(CallbackInfo ci) {
        me.cortex.voxy.client.compat.create.AzimuthBehaviourIndex.register(this.parentVisual, this::collectCrumblingInstances);
    }
}
