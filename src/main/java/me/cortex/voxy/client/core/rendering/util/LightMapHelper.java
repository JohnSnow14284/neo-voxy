package me.cortex.voxy.client.core.rendering.util;

import net.minecraft.client.Minecraft;

import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_WRAP_R;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;
import static org.lwjgl.opengl.GL45C.glCreateSamplers;

//Sample the lightmap through a dedicated sampler rather than whatever state sampler unit 0 happens to
//carry. LINEAR min/mag, CLAMP_TO_EDGE on all three axes; with the shader taking base level only, this
//pins the lightmap's mip selection, which otherwise jitters at LOD range and shows as flickering blocks
//under shaders.
public class LightMapHelper {
    private static final int LM_SAMPLER = glCreateSamplers();

    static {
        glSamplerParameteri(LM_SAMPLER, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glSamplerParameteri(LM_SAMPLER, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glSamplerParameteri(LM_SAMPLER, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(LM_SAMPLER, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(LM_SAMPLER, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
    }

    public static void bind(int lightingIndex) {
        glBindSampler(lightingIndex, LM_SAMPLER);
        glBindTextureUnit(lightingIndex, getLightmapTextureId());
    }

    public static int getLightmapTextureId() {
        return Minecraft.getInstance().gameRenderer.lightTexture().lightTexture.getId();
    }
}
