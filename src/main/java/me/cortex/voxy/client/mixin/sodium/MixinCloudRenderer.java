package me.cortex.voxy.client.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.caffeinemc.mods.sodium.client.render.immediate.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = {CloudRenderer.class}, remap = false, priority = 1100)
public class MixinCloudRenderer {
    @WrapMethod(method = {"getCloudRenderDistance"})
    private static int voxy$cloudRenderDistance(Operation<Integer> original) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled())
            return original.call();
        //Hand vanilla's distance back whenever something is restricting vision. This value does not
        //only size the clouds: CloudRenderer.render feeds it to FogRenderer.setupFog as
        //renderDistance * 8, so raising it raises the fog that call computes. Sodium saves and restores
        //around its own call, but sodium-extra injects another setupFog at the head of renderClouds
        //and does not restore, so the inflated value survives into the terrain fog that
        //ChunkShaderFogComponent hands the chunk shaders - and blindness/darkness stop darkening the
        //world. Clouds are not visible through blindness anyway, so there is nothing to lose here.
        if (VoxyRenderSystem.visionEffectPresent())
            return original.call();
        if (VoxyConfig.CONFIG.adaptCloudDistance) {
            return Math.clamp((int)(VoxyConfig.CONFIG.sectionRenderDistance * 32F) + 9, original.call(), 265);
        }
        return VoxyConfig.CONFIG.cloudDistance < 1 ? original.call() : VoxyConfig.CONFIG.cloudDistance + 9;
    }
}
