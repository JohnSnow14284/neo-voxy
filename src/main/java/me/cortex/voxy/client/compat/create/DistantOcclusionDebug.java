package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11C.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11C.glReadPixels;
import static org.lwjgl.opengl.GL45C.glGetTextureSubImage;

//Per-frame occlusion recorder for the distant track/train meshes. While armed it samples, inside
//the render hook, the 3x3 centre depth/stencil before the meshes draw (what they depth-test
//against), the same pixels after, the vanilla source depth at the centre with its CPU-side
//reprojection, and every track unit's handover verdict. Aim the crosshair at a broken spot, walk
//the transition band, and the dump tells which link broke: setup classified the pixel empty
//(stencil 1 + depth 1.0 while vanilla depth says otherwise), LOD depth missing (stencil 1, pre
//depth 1.0, no vanilla cover), reprojection losing to the mesh (stencil 0, pre==reproj, post==
//mesh), or the handover gates flapping frame to frame.
public final class DistantOcclusionDebug {
    private static final int MAX_FRAMES = 1500;
    private static final int MAX_UNITS_PER_FRAME = 40;
    private static final int GL_STENCIL_INDEX = 0x1901;

    private static volatile long stopAtMs;
    private static long startedAtMs;
    private static long frameCounter;
    private static boolean headerWritten;

    private static final ArrayDeque<String> FRAMES = new ArrayDeque<>();
    private static final List<String> UNIT_LINES = new ArrayList<>();
    private static final StringBuilder HEADER = new StringBuilder();

    private DistantOcclusionDebug() {}

    public static boolean isActive() {
        return System.currentTimeMillis() < stopAtMs;
    }

    public static String start(int seconds) {
        synchronized (FRAMES) {
            FRAMES.clear();
            UNIT_LINES.clear();
            HEADER.setLength(0);
            headerWritten = false;
            frameCounter = 0;
            startedAtMs = System.currentTimeMillis();
            stopAtMs = startedAtMs + seconds * 1000L;
        }
        return "occlusion recorder armed for " + seconds + "s - aim the crosshair at the broken track/train and walk the transition band; the dump lands in the game dir when time is up (or rerun to stop early)";
    }

    public static String stopAndDump() {
        stopAtMs = 0;
        return dump();
    }

    //Called by DistantTrackRenderer for every unit inside render distance while armed.
    //graced = raw compiled just flipped false but the hysteresis is still holding the handover.
    static void logUnit(double x, double y, double z, boolean bezier, double dist, boolean rawCompiled, boolean graced, boolean vanillaDraws) {
        if (UNIT_LINES.size() < MAX_UNITS_PER_FRAME) {
            UNIT_LINES.add(String.format("  U (%d,%d,%d) %s d=%d compiled=%d%s %s",
                    (int) x, (int) y, (int) z, bezier ? "bez" : "str", (int) dist,
                    rawCompiled ? 1 : 0, graced ? "+g" : "", vanillaDraws ? "SKIP" : "DRAW"));
        } else if (UNIT_LINES.size() == MAX_UNITS_PER_FRAME) {
            UNIT_LINES.add("  U ... (more suppressed)");
        }
    }

    public static final LodPipelineHooks.FrameDebugProbe PROBE = new LodPipelineHooks.FrameDebugProbe() {
        private final float[] preDepth = new float[9];
        private final int[] preStencil = new int[9];
        private final float[] postDepth = new float[9];
        private final int[] postStencil = new int[9];
        private final float[] vanillaDepth = new float[1];
        private final Matrix4f matScratch = new Matrix4f();
        private final Vector4f vecScratch = new Vector4f();

        @Override
        public void begin(AbstractRenderPipeline pipeline, Viewport<?> viewport) {
            if (!isActive()) {
                return;
            }
            UNIT_LINES.clear();
            readCentre(viewport, this.preDepth, this.preStencil);
        }

        @Override
        public void end(AbstractRenderPipeline pipeline, Viewport<?> viewport) {
            if (stopAtMs == 0) {
                return;
            }
            if (!isActive()) {
                //Time expired: flush once from the render thread
                stopAtMs = 0;
                String msg = dump();
                var mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.gui.getChat().addMessage(Component.literal("[voxy] " + msg));
                }
                return;
            }
            frameCounter++;
            readCentre(viewport, this.postDepth, this.postStencil);

            //Vanilla source depth at the centre pixel: same-pixel mapping (UV*scaleFactor*srcSize
            //== window px), clamped at the edge like the sampler
            int cx = Math.min(viewport.width / 2, Math.max(0, pipeline.debugSrcWidth() - 1));
            int cy = Math.min(viewport.height / 2, Math.max(0, pipeline.debugSrcHeight() - 1));
            this.vanillaDepth[0] = Float.NaN;
            try {
                glGetTextureSubImage(pipeline.debugSourceDepthTex(), 0, cx, cy, 0, 1, 1, 1,
                        GL_DEPTH_COMPONENT, GL_FLOAT, this.vanillaDepth);
            } catch (Throwable ignored) {
            }

            //CPU replay of the setup pass's reprojection for the centre pixel
            float reproj = Float.NaN;
            float van = this.vanillaDepth[0];
            if (!Float.isNaN(van)) {
                boolean halfNdc = RenderProperties.windowIsHalfNdc();
                float srcNdc = halfNdc ? van * 2.0f - 1.0f : van;
                this.matScratch.set(viewport.vanillaProjection).mul(viewport.modelView).invert();
                this.vecScratch.set(0.0f, 0.0f, srcNdc, 1.0f);//centre pixel: ndc x=y=0
                this.matScratch.transform(this.vecScratch);
                this.vecScratch.div(this.vecScratch.w);
                this.vecScratch.w = 1.0f;
                viewport.MVP.transform(this.vecScratch);
                float ndc = this.vecScratch.z / this.vecScratch.w;
                reproj = Math.max(0.0f, Math.min(1.0f - (2.0f / ((1 << 24) - 1)),
                        halfNdc ? ndc * 0.5f + 0.5f : ndc));
            }

            if (!headerWritten) {
                headerWritten = true;
                HEADER.append("pipeline=").append(pipeline.getClass().getSimpleName())
                        .append(" viewport=").append(viewport.width).append('x').append(viewport.height)
                        .append(" src=").append(pipeline.debugSrcWidth()).append('x').append(pipeline.debugSrcHeight())
                        .append(" halfNdc=").append(RenderProperties.windowIsHalfNdc())
                        .append(" voxyNear=").append(String.format("%.1f", planeFromProj(viewport.projection, true)))
                        .append(" voxyFar=").append(String.format("%.0f", planeFromProj(viewport.projection, false)));
            }

            var mc = Minecraft.getInstance();
            var cam = mc.gameRenderer.getMainCamera().getPosition();
            var sb = new StringBuilder(256);
            sb.append("f=").append(frameCounter)
                    .append(" t=+").append(System.currentTimeMillis() - startedAtMs).append("ms")
                    .append(" cam=(").append((int) cam.x).append(',').append((int) cam.y).append(',').append((int) cam.z).append(')')
                    .append(" pre[c=").append(fmtVoxy(this.preDepth[4], viewport)).append(" mn=").append(fmt(min(this.preDepth))).append(" mx=").append(fmt(max(this.preDepth)))
                    .append(" s=").append(stencilSummary(this.preStencil)).append(']')
                    .append(" post[c=").append(fmtVoxy(this.postDepth[4], viewport)).append(" s=").append(stencilSummary(this.postStencil)).append(']')
                    .append(" van=").append(fmtVan(van, viewport))
                    .append(" reproj=").append(fmt(reproj))
                    .append(" tilesDrawn=").append(DistantTrackRenderer.lastFrameTilesDrawn)
                    .append(" carriages=").append(DistantTrainRenderer.lastFrameCarriagesDrawn);
            for (String unit : UNIT_LINES) {
                sb.append('\n').append(unit);
            }
            synchronized (FRAMES) {
                FRAMES.addLast(sb.toString());
                if (FRAMES.size() > MAX_FRAMES) {
                    FRAMES.removeFirst();
                }
            }
        }
    };

    private static void readCentre(Viewport<?> viewport, float[] depth, int[] stencil) {
        int x = Math.max(0, viewport.width / 2 - 1);
        int y = Math.max(0, viewport.height / 2 - 1);
        try {
            glReadPixels(x, y, 3, 3, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
            glReadPixels(x, y, 3, 3, GL_STENCIL_INDEX, GL_UNSIGNED_INT, stencil);
        } catch (Throwable e) {
            java.util.Arrays.fill(depth, Float.NaN);
            java.util.Arrays.fill(stencil, -1);
        }
    }

    private static String dump() {
        List<String> frames;
        synchronized (FRAMES) {
            frames = new ArrayList<>(FRAMES);
            FRAMES.clear();
        }
        if (frames.isEmpty()) {
            return "occlusion recorder stopped - nothing captured (was the hook rendering at all?)";
        }
        File out = new File(Minecraft.getInstance().gameDirectory, "voxy-occlusion-debug.txt");
        try (PrintWriter w = new PrintWriter(out, StandardCharsets.UTF_8)) {
            w.println("# distant mesh occlusion capture, " + frames.size() + " frames");
            w.println("# pre = centre 3x3 depth/stencil BEFORE the meshes draw (what they test against)");
            w.println("# post = same pixels after; van = vanilla source depth at centre; reproj = CPU replay of setup's reprojected value");
            w.println("# stencil: 0=vanilla-covered 1=empty/LOD-allowed 3=mesh-tagged");
            w.println("# units: per track unit - compiled=isSectionCompiled(gate), SKIP=handover to vanilla, DRAW=we draw it");
            w.println("# " + HEADER);
            for (String f : frames) {
                w.println(f);
            }
        } catch (Exception e) {
            Logger.error("occlusion debug dump failed", e);
            return "dump failed: " + e;
        }
        return "occlusion capture written: " + out.getAbsolutePath() + " (" + frames.size() + " frames)";
    }

    private static String fmt(float v) {
        return Float.isNaN(v) ? "nan" : String.format("%.6f", v);
    }

    //Window depth -> view-space distance in blocks, so a dump line reads directly: is the surface
    //the crosshair sees at the leaves' distance or at the terrain behind them?
    private static float depthToDistance(float windowDepth, org.joml.Matrix4fc proj) {
        if (Float.isNaN(windowDepth) || windowDepth >= 1.0f || windowDepth <= 0.0f) {
            return Float.NaN;
        }
        float ndc = RenderProperties.windowIsHalfNdc() ? windowDepth * 2.0f - 1.0f : windowDepth;
        //ndc = (a*z + b) / (-z) with a=m22, b=m32 -> z = -b/(ndc + a); distance = |z|
        float denom = ndc + proj.m22();
        if (Math.abs(denom) < 1e-9f) {
            return Float.NaN;
        }
        return Math.abs(proj.m32() / denom);
    }

    //Near/far plane recovered from voxy's [0,1]-ndc projection depth row (near at ndc 0, far at 1)
    private static float planeFromProj(org.joml.Matrix4fc proj, boolean near) {
        float denom = (near ? 0.0f : 1.0f) + proj.m22();
        return Math.abs(denom) < 1e-9f ? Float.POSITIVE_INFINITY : Math.abs(proj.m32() / denom);
    }

    private static String fmtVoxy(float v, Viewport<?> viewport) {
        float d = depthToDistance(v, viewport.projection);
        return Float.isNaN(d) ? fmt(v) : fmt(v) + "(" + String.format("%.0f", d) + "m)";
    }

    private static String fmtVan(float v, Viewport<?> viewport) {
        float d = depthToDistance(v, viewport.vanillaProjection);
        return Float.isNaN(d) ? fmt(v) : fmt(v) + "(" + String.format("%.0f", d) + "m)";
    }

    private static float min(float[] a) {
        float m = a[0];
        for (float v : a) m = Math.min(m, v);
        return m;
    }

    private static float max(float[] a) {
        float m = a[0];
        for (float v : a) m = Math.max(m, v);
        return m;
    }

    private static String stencilSummary(int[] s) {
        //Distinct values in reading order, e.g. "0" or "0/1" or "1/3"
        var sb = new StringBuilder();
        long seen = 0;
        for (int v : s) {
            if (v >= 0 && v < 64 && (seen & (1L << v)) == 0) {
                seen |= 1L << v;
                if (sb.length() > 0) sb.append('/');
                sb.append(v);
            }
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }
}
