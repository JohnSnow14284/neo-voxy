package me.cortex.voxy.client.mixin.fakesight;

import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.InputStream;

/**
 * Allows Sodium's shader loader to resolve resources from the active class loader.
 * This mirrors FakeSight's Sodium shader-loader compatibility fix and is harmless
 * when Sodium's own lookup already succeeds.
 */
@Mixin(value = ShaderLoader.class, remap = false)
public class MixinShaderLoader {
    @Redirect(
            method = "getShaderSource(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/String;",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Class;getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;"
            ),
            require = 0
    )
    private static InputStream voxy$redirectGetResourceAsStream(Class<?> clazz, String path) {
        return ShaderLoader.class.getClassLoader().getResourceAsStream(path);
    }
}
