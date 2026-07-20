package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.BogeyPose;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ShapeBogey;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.joml.Matrix4f;
import org.joml.Math;

import java.util.List;

import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_EQUAL;
import static org.lwjgl.opengl.GL11C.GL_KEEP;
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

//Draws distant train carriages as rigid meshes in the self-contained distant vertex format, inside
//voxy's render pipeline on both backends (LOD-depth perfect occlusion; iris uses the patched shader).
public final class DistantTrainRenderer implements LodPipelineHooks.Renderer {
    //Resolves a bogey description to its captured snapshot mesh; set from Voxy init when Create is
    //present (the provider class touches Create's registries).
    public static java.util.function.Function<ShapeBogey, DistantMesh> bogeyMeshProvider;
    //Diagnostics for /voxy debug trains: how many carriages the last frame actually drew
    public static volatile int lastFrameCarriagesDrawn;

    //Handover boundary shared with the live-side culls - see TrainHandover.

    @Override
    public void render(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        //Both pipelines: draw into the pipeline's opaque target. Vertices are camera-relative world
        //space and voxy's combined view-projection puts depth in the same space as the LOD terrain,
        //so occlusion is per-pixel. On the iris pipeline the patched shader fills the g-buffer;
        //bogeys still go through vanilla-style buffers, which cannot, so they skip there for now.
        pipeline.setupAndBindOpaque(viewport);
        //renderCommon only reads viewProjection (copies via transform.set), never mutates it
        this.renderCommon(pipeline, viewport.MVP,
                viewport.cameraX, viewport.cameraY, viewport.cameraZ, depthFunc);
    }

    private void renderCommon(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Matrix4f viewProjection, double camX, double camY, double camZ, int depthFunc) {
        lastFrameCarriagesDrawn = 0;
        if (DistantTrainManager.isEmpty()) {
            return;
        }
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantTrains) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        var dimension = mc.level.dimension().location();
        double maxDist = cfg.createRenderDistance(cfg.distantTrainMaxChunks);
        double maxDistSq = maxDist * maxDist;
        //Hand over at min(carriage tracking cap, full view distance) - the vanilla->LOD transition.
        //The live culls use the same boundary so exactly one representation draws on either side.
        double handover = TrainHandover.handoverDist();
        double handoverSq = handover * handover;
        long now = System.nanoTime();
        long nowMs = System.currentTimeMillis();

        boolean renderStateActive = false;
        int drawn = 0;
        var transform = new Matrix4f();
        var scratchPos = new net.minecraft.core.BlockPos.MutableBlockPos();

        try {
            for (var train : DistantTrainManager.trains().values()) {
                if (!dimension.equals(train.dimension)) {
                    continue;
                }
                for (var track : train.carriages.values()) {
                    if (track.cur == null) {
                        continue;
                    }
                    //Interpolate into locals - no per-carriage CarriagePose allocation, and done
                    //before the maxDistSq cull so far carriages (still in the client map out to the
                    //server stream range) cost nothing but a few lerps.
                    float t = interpFactor(track, now);
                    var cur = track.cur;
                    var prev = track.prev;
                    double px, py, pz;
                    float yaw, pitch;
                    if (prev == null || prev == cur) {
                        px = cur.x(); py = cur.y(); pz = cur.z();
                        yaw = cur.yaw(); pitch = cur.pitch();
                    } else {
                        px = Mth.lerp(t, prev.x(), cur.x());
                        py = Mth.lerp(t, prev.y(), cur.y());
                        pz = Mth.lerp(t, prev.z(), cur.z());
                        yaw = prev.yaw() + Mth.wrapDegrees(cur.yaw() - prev.yaw()) * t;
                        pitch = Math.lerp(prev.pitch(), cur.pitch(), t);
                    }
                    double dx = px - camX, dy = py - camY, dz = pz - camZ;
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > maxDistSq) {
                        continue;
                    }
                    //Hand over to Create by the same rule vanilla uses to skip entities: the section
                    //must be compiled AND the carriage must be close enough for its entity to still
                    //be tracked. Only meaningful inside the tracking band - beyond it we always draw,
                    //so the section lookup + BlockPos are skipped for the distant carriages that are
                    //this renderer's whole purpose. The grace absorbs sodium's lazy recompiles.
                    if (distSq < handoverSq) {
                        if (mc.levelRenderer.isSectionCompiled(scratchPos.set(
                                (int) java.lang.Math.floor(px), (int) java.lang.Math.floor(py), (int) java.lang.Math.floor(pz)))) {
                            track.lastCompiledMs = nowMs;
                        }
                        if (nowMs - track.lastCompiledMs < 400) {
                            continue;
                        }
                    }
                    var entry = DistantTrainManager.shape(track.shapeId);
                    if (entry == null) {
                        continue;
                    }

                    //Real cached light at the carriage position (voxy voxel store), at most 1s stale
                    if (track.lightPacked < 0 || nowMs - track.lightSampledAtMs > 1000) {
                        track.lightPacked = DistantLightSampler.sample(mc.level,
                                (int) java.lang.Math.floor(px), (int) java.lang.Math.floor(py), (int) java.lang.Math.floor(pz));
                        track.lightSampledAtMs = nowMs;
                    }

                    if (!renderStateActive) {
                        DistantShaders.forPipeline(pipeline, true).bind();
                        DistantShaders.bindTextures();
                        glEnable(GL_DEPTH_TEST);
                        glDepthFunc(depthFunc);
                        glDepthMask(true);
                        glDisable(GL_CULL_FACE);
                        //Only draw into pixels owned by the LOD pipeline. Vanilla-owned pixels use
                        //a separately reprojected depth which some shader packs compare differently;
                        //writing there caused distant trains to expose the terrain behind them.
                        glEnable(GL_STENCIL_TEST);
                        glStencilFunc(GL_EQUAL, 1, 0x1);
                        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
                        renderStateActive = true;
                    }

                    //Mirror of OrientedContraptionEntity.applyLocalTransforms: translate(-.5,0,-.5),
                    //center, rotY(viewYRot), rotZ(viewXRot), rotY(initialYaw), uncenter - the first
                    //two translations fold into (0, 0.5, 0). getViewYRot returns the negated yaw
                    //field, so the negation belongs here; pitch passes through unnegated.
                    transform.set(viewProjection)
                            .translate((float) dx, (float) dy + 0.5f, (float) dz)
                            .rotateY((float) java.lang.Math.toRadians(-yaw))
                            .rotateZ((float) java.lang.Math.toRadians(pitch))
                            .rotateY((float) java.lang.Math.toRadians(entry.initialYaw()))
                            .translate(-0.5f, -0.5f, -0.5f);
                    DistantShaders.uploadTransform(transform);
                    glUniform2f(4,
                            (DistantLightSampler.block(track.lightPacked) * 16 + 8) / 256.0f,
                            (DistantLightSampler.sky(track.lightPacked) * 16 + 8) / 256.0f);
                    //Net model yaw of the transform chain above (pitch omitted - low grades)
                    DistantShaders.uploadFaceRotation(-yaw + entry.initialYaw());
                    entry.mesh().mesh.draw();
                    drawn++;

                    //Bogeys draw as captured snapshot meshes through the same shader (light uniform
                    //is already set to the carriage's); works identically on both pipelines
                    if (bogeyMeshProvider != null && !entry.bogeys().isEmpty()) {
                        drawBogeys(track, entry, t, camX, camY, camZ, viewProjection, transform);
                    }
                }
            }

            if (renderStateActive) {
                glBindVertexArray(0);
                glUseProgram(0);
            }
        } finally {
            if (renderStateActive) {
                //Pipeline contract state (set by initDepthStencil, expected by every later pass)
                glStencilFunc(GL_EQUAL, 1, 0x1);
                glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            }
            lastFrameCarriagesDrawn = drawn;
        }
    }

    @SubscribeEvent
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        DistantTrainManager.clearAll();
    }

    //Interpolates each bogey pose and draws its snapshot mesh. Transform mirrors the tail of
    //CarriageContraptionEntityRenderer.translateBogey (anchor -> yaw -> pitch -> +0.5y -> roll);
    //the -1.5078125 style offset is baked into the captured mesh. Wheel spin is a P4 follow-up.
    private static void drawBogeys(DistantTrainManager.CarriageTrack track, DistantTrainManager.ShapeEntry entry,
                                   float t, double camX, double camY, double camZ, Matrix4f viewProjection, Matrix4f transform) {
        List<BogeyPose> cur = track.cur.bogeys();
        List<BogeyPose> prev = track.prev != null ? track.prev.bogeys() : cur;
        int count = java.lang.Math.min(cur.size(), entry.bogeys().size());
        for (int i = 0; i < count; i++) {
            var mesh = bogeyMeshProvider.apply(entry.bogeys().get(i));
            if (mesh == null) {
                continue;
            }
            BogeyPose c = cur.get(i);
            BogeyPose p = i < prev.size() ? prev.get(i) : c;
            double x = Mth.lerp(t, p.x(), c.x());
            double y = Mth.lerp(t, p.y(), c.y());
            double z = Mth.lerp(t, p.z(), c.z());
            float yaw = p.yaw() + Mth.wrapDegrees(c.yaw() - p.yaw()) * t;
            float pitch = Math.lerp(p.pitch(), c.pitch(), t);

            transform.set(viewProjection)
                    .translate((float) (x - camX), (float) (y - camY), (float) (z - camZ))
                    .rotateY((float) java.lang.Math.toRadians(yaw))
                    .rotateX((float) java.lang.Math.toRadians(pitch))
                    .translate(0.0f, 0.5f, 0.0f);
            if (c.upsideDown()) {
                transform.rotateZ((float) java.lang.Math.PI);
            }
            DistantShaders.uploadTransform(transform);
            DistantShaders.uploadFaceRotation(yaw);
            mesh.draw();
        }
    }

    private static float interpFactor(DistantTrainManager.CarriageTrack track, long now) {
        if (track.prev == null || track.prev == track.cur) {
            return 1.0f;
        }
        //Allow slight extrapolation past the newest sample so motion stays continuous between packets
        return Mth.clamp((now - track.curTimeNanos) / (float) track.sampleIntervalNanos, 0.0f, 1.25f);
    }
}
