package me.cortex.voxy.client.compat;

import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_FUNC;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_WRITEMASK;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL11C.glDepthFunc;
import static org.lwjgl.opengl.GL11C.glDepthMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glIsEnabled;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING;

//Render hook that fires inside the LOD pipeline, after opaque LOD terrain and right before the
//translucent pass, with the pipeline's framebuffer bound. Its depth attachment holds the full LOD
//depth in voxy's far-projection space, so geometry drawn here is occluded by (and occludes) LOD
//terrain naturally; the colour is carried to the screen by the pipeline's final composite. Works
//on both the normal and the shader pipeline since both share this base-class path.
public final class LodPipelineHooks {
    public interface Renderer {
        //depthFunc is the pipeline's closer-or-equal compare - GEQUAL under reverse-Z, LEQUAL otherwise
        void render(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc);
    }

    //Frame recorder for occlusion debugging: begin() samples the depth/stencil state the renderers
    //are about to test against, end() samples what they left behind. Registered by the compat side.
    public interface FrameDebugProbe {
        void begin(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport);
        void end(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport);
    }

    public static volatile FrameDebugProbe frameDebugProbe;

    private static final List<Renderer> RENDERERS = new CopyOnWriteArrayList<>();
    private static boolean errored;

    //One-shot depth probe for /voxy debug trains: reads back the depth state and the centre pixels
    //of the depth attachment right after our draws, settling "was LOD depth actually there".
    public static volatile boolean depthProbeRequested;
    public static volatile String depthProbeResult;

    private LodPipelineHooks() {}

    public static void register(Renderer renderer) {
        RENDERERS.add(renderer);
    }

    public static void beforeTranslucent(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        if (RENDERERS.isEmpty()) {
            return;
        }
        //Our draws sit between the pipeline's opaque and translucent passes; any state we leave
        //behind corrupts the next pass's geometry. Capture and restore everything the renderers touch.
        renderStateGuarded(() -> {
            var probe = frameDebugProbe;
            if (probe != null) {
                try {
                    probe.begin(pipeline, viewport);
                } catch (Throwable ignored) {
                }
            }
            for (Renderer renderer : RENDERERS) {
                try {
                    renderer.render(pipeline, viewport, depthFunc);
                } catch (Throwable e) {
                    if (!errored) {
                        errored = true;
                        Logger.error("LOD pipeline render hook failed (logged once)", e);
                    }
                }
            }
            if (probe != null) {
                try {
                    probe.end(pipeline, viewport);
                } catch (Throwable ignored) {
                }
            }
            if (depthProbeRequested) {
                depthProbeRequested = false;
                depthProbeResult = captureDepthProbe(viewport, depthFunc);
                Logger.info("Depth probe: " + depthProbeResult);
            }
        });
    }

    private static String captureDepthProbe(Viewport<?> viewport, int expectedFunc) {
        try {
            boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
            boolean mask = glGetInteger(GL_DEPTH_WRITEMASK) != 0;
            int func = glGetInteger(GL_DEPTH_FUNC);
            int drawFbo = glGetInteger(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            float[] px = new float[9];
            org.lwjgl.opengl.GL11C.glReadPixels(
                    Math.max(0, viewport.width / 2 - 1), Math.max(0, viewport.height / 2 - 1), 3, 3,
                    org.lwjgl.opengl.GL11C.GL_DEPTH_COMPONENT, org.lwjgl.opengl.GL11C.GL_FLOAT, px);
            var sb = new StringBuilder("fbo=").append(drawFbo)
                    .append(" depthTest=").append(depthTest)
                    .append(" mask=").append(mask)
                    .append(" func=0x").append(Integer.toHexString(func))
                    .append(" expectedFunc=0x").append(Integer.toHexString(expectedFunc))
                    .append(" centreDepth=[");
            for (int i = 0; i < 9; i++) {
                sb.append(String.format("%.5f", px[i]));
                if (i < 8) {
                    sb.append(' ');
                }
            }
            return sb.append(']').toString();
        } catch (Throwable e) {
            return "probe failed: " + e;
        }
    }

    //Runs body with a full capture/restore of the GL state our renderers mutate. Program and VAO
    //restore through GlStateManager (unconditional binds, resyncs its caches); textures restore
    //through raw GL - GlStateManager._bindTexture skips the real call when its cache already holds
    //the requested id, which is exactly the post-hook situation (cache==pre-hook id, reality==ours).
    public static void renderStateGuarded(Runnable body) {
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        int[] prevTextures = new int[4];
        for (int unit = 0; unit < 4; unit++) {
            glActiveTexture(GL_TEXTURE0 + unit);
            prevTextures[unit] = glGetInteger(GL_TEXTURE_BINDING_2D);
        }
        glActiveTexture(prevActiveTexture);
        boolean prevDepthTest = glIsEnabled(GL_DEPTH_TEST);
        int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
        boolean prevDepthMask = glGetInteger(GL_DEPTH_WRITEMASK) != 0;
        boolean prevCull = glIsEnabled(GL_CULL_FACE);
        boolean prevBlend = glIsEnabled(GL_BLEND);
        try {
            body.run();
        } finally {
            //Raw binds: reality returns to the captured ids, which is what GlStateManager's cache
            //still holds - cache and reality resync without going through its skip-if-cached path
            for (int unit = 0; unit < 4; unit++) {
                glActiveTexture(GL_TEXTURE0 + unit);
                org.lwjgl.opengl.GL11C.glBindTexture(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, prevTextures[unit]);
            }
            glActiveTexture(prevActiveTexture);
            GlStateManager._glUseProgram(prevProgram);
            GlStateManager._glBindVertexArray(prevVao);
            if (prevDepthTest) {
                glEnable(GL_DEPTH_TEST);
            } else {
                glDisable(GL_DEPTH_TEST);
            }
            glDepthFunc(prevDepthFunc);
            glDepthMask(prevDepthMask);
            if (prevCull) {
                glEnable(GL_CULL_FACE);
            } else {
                glDisable(GL_CULL_FACE);
            }
            if (prevBlend) {
                glEnable(GL_BLEND);
            } else {
                glDisable(GL_BLEND);
            }
        }
    }

    //The pipeline drives GL directly, desyncing vanilla's cached bindings: a later
    //ShaderInstance.apply / VertexBuffer.bind may think its texture/program/VAO is still bound and
    //skip the rebind, sampling garbage or drawing with the pipeline's own program and vertex
    //layout. Zeroing the caches forces every following bind to genuinely happen.
    public static void invalidateGlCaches() {
        for (int unit = 0; unit < 4; unit++) {
            GlStateManager._activeTexture(GL_TEXTURE0 + unit);
            GlStateManager._bindTexture(0);
        }
        GlStateManager._activeTexture(GL_TEXTURE0);
        GlStateManager._glUseProgram(0);
        GlStateManager._glBindVertexArray(0);
    }
}
