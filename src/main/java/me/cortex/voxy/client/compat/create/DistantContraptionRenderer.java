package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_EQUAL;
import static org.lwjgl.opengl.GL11C.GL_KEEP;
import static org.lwjgl.opengl.GL11C.GL_REPLACE;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11C.glDepthFunc;
import static org.lwjgl.opengl.GL11C.glDepthMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glStencilFunc;
import static org.lwjgl.opengl.GL11C.glStencilOp;
import static org.lwjgl.opengl.GL20C.glUniform2f;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

//Draws the frozen distant-contraption snapshots (see DistantContraptionManager) as static rigid
//meshes inside the LOD pipeline, exactly where the vanilla/Flywheel render stops. A snapshot's stored
//`local` is already the full applyLocalTransforms matrix, so the draw is just VP · translate(pos-cam)
//· local - no per-subclass rebuild. Same distant vertex format, stencil tag and light-uniform path as
//the train renderer; occlusion against LOD terrain is per-pixel via the shared depth.
public final class DistantContraptionRenderer implements LodPipelineHooks.Renderer {
    public static volatile int lastFrameDrawn;

    //Snapshot refresh runs on the client tick, not in the render hook: baking uploads to GL, which
    //mid-pipeline would clobber the LOD buffer setup (same reason DistantTrackRenderer bakes on tick).
    @net.neoforged.bus.api.SubscribeEvent
    public void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        var cam = mc.gameRenderer.getMainCamera().getPosition();
        //LOD radius (capped by distantContraptionMaxChunks), not the vanilla render distance: loaded
        //contraptions past the view distance (deep-mine machines below a short render distance) still
        //refresh so they animate in the LOD. Same cap the draw uses, so we never snapshot what we will
        //not draw.
        double maxDist = VoxyConfig.CONFIG.createRenderDistance(VoxyConfig.CONFIG.distantContraptionMaxChunks);
        DistantContraptionManager.update(mc.level, cam.x, cam.y, cam.z, maxDist);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        DistantContraptionManager.clearAll();
    }

    //No removal-event handling: on the client a real disassembly and the server's entity tracker
    //letting go both arrive as the same DISCARDED removal, and tracking ranges vary per server, so any
    //distance heuristic here guesses wrong somewhere. Presence-based cleanup in the manager's update
    //covers disassembly instead: very near the player the entity is always tracked, so a snapshot with
    //no live entity there is a structure that no longer exists.

    @Override
    public void render(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        pipeline.setupAndBindOpaque(viewport);
        this.renderCommon(pipeline, viewport, viewport.MVP, viewport.cameraX, viewport.cameraY, viewport.cameraZ, depthFunc);
    }

    private void renderCommon(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport, Matrix4f viewProjection,
                              double camX, double camY, double camZ, int depthFunc) {
        lastFrameDrawn = 0;
        var snapshots = DistantContraptionManager.snapshots();
        if (snapshots.isEmpty()) {
            return;
        }
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantContraptions) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        var dimension = mc.level.dimension().location();
        double maxDist = cfg.createRenderDistance(cfg.distantContraptionMaxChunks);
        double maxDistSq = maxDist * maxDist;
        //The vanilla/Flywheel render (and its anti-float cull) owns everything inside the render
        //distance; we take over exactly at that radius, holding the frozen snapshot.
        double reach = mc.options.getEffectiveRenderDistance() * 16.0;
        double reachSq = reach * reach;

        boolean renderStateActive = false;
        int drawn = 0;
        var transform = new Matrix4f();
        try {
            for (var snap : snapshots.values()) {
                if (snap.mesh() == null || !dimension.equals(snap.dim())) {
                    continue;
                }
                double dx = snap.x() - camX, dy = snap.y() - camY, dz = snap.z() - camZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                //Yield to the live entity only when it actually exists client-side: entity tracking
                //ends well inside the render distance, so yielding on distance alone left a ring
                //(tracking range -> render distance) where neither side drew.
                if ((distSq < reachSq && snap.live()) || distSq > maxDistSq) {
                    continue;
                }
                //Before any state setup, so a frame with every contraption behind the camera never binds
                //the shader. The bounds are contraption-local and the frozen pose can rotate them, hence
                //going through the transform rather than testing an axis-aligned box at the origin.
                if (viewport != null && !DistantVisibility.isTransformedBoxVisible(
                        viewport, snap.local(), snap.x(), snap.y(), snap.z(), snap.mesh().localBounds)) {
                    continue;
                }

                if (!renderStateActive) {
                    DistantShaders.forPipeline(pipeline, true).bind();
                    DistantShaders.bindTextures();
                    glEnable(GL_DEPTH_TEST);
                    glDepthFunc(depthFunc);
                    glDepthMask(true);
                    glDisable(GL_CULL_FACE);
                    glEnable(GL_STENCIL_TEST);
                    glStencilFunc(GL_ALWAYS, 3, 0xFF);
                    glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
                    renderStateActive = true;
                }

                //VP · T(worldPos - camera) · M_local  (M_local = applyLocalTransforms, frozen)
                transform.set(viewProjection).translate((float) dx, (float) dy, (float) dz).mul(snap.local());
                DistantShaders.uploadTransform(transform);
                int light = snap.lightPacked();
                glUniform2f(4,
                        (DistantLightSampler.block(light) * 16 + 8) / 256.0f,
                        (DistantLightSampler.sky(light) * 16 + 8) / 256.0f);
                snap.mesh().mesh.draw();
                drawn++;
            }
            if (renderStateActive) {
                glBindVertexArray(0);
                glUseProgram(0);
            }
        } finally {
            if (renderStateActive) {
                //Restore the pipeline's ambient stencil contract
                glStencilFunc(GL_EQUAL, 1, 0x1);
                glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            }
            lastFrameDrawn = drawn;
        }
    }
}
