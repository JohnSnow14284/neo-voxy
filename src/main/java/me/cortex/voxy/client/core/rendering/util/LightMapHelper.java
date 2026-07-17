package me.cortex.voxy.client.core.rendering.util;

import net.minecraft.client.Minecraft;

import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_R;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL33.glSamplerParameteri;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;
import static org.lwjgl.opengl.GL45.glCreateSamplers;

public class LightMapHelper {
    private static final int LIGHTMAP_SAMPLER;
    static {
        LIGHTMAP_SAMPLER = glCreateSamplers();
        glSamplerParameteri(LIGHTMAP_SAMPLER, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glSamplerParameteri(LIGHTMAP_SAMPLER, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glSamplerParameteri(LIGHTMAP_SAMPLER, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(LIGHTMAP_SAMPLER, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(LIGHTMAP_SAMPLER, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
    }

    public static void bind(int lightingIndex) {
        glBindSampler(lightingIndex, LIGHTMAP_SAMPLER);
        glBindTextureUnit(lightingIndex, getLightmapTextureId());
    }

    public static int getLightmapTextureId() {
        return Minecraft.getInstance().gameRenderer.lightTexture().lightTexture.getId();
    }
}
