package me.cortex.voxy.client.compat.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBogeyStyles;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ShapeBogey;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.util.HashMap;
import java.util.Map;

//Captures Create bogey renderers (incl. addon styles) into the distant vertex format: the style
//renderer streams its vertices into a capture consumer once (wheel angle 0), producing a static
//snapshot mesh that both pipelines can draw with the distant shader. This replaces the vanilla
//buffer path, which could not fill the iris g-buffer. Wheel spin is lost in the snapshot for now.
public final class DistantBogeyMeshes {
    private static final Map<String, DistantMesh> CACHE = new HashMap<>();
    private static boolean errored;

    private DistantBogeyMeshes() {}

    //Null when the style is unknown client side or capture failed (cached either way)
    public static DistantMesh getOrCapture(ShapeBogey info) {
        String key = info.styleId() + "|" + info.sizeId();
        if (CACHE.containsKey(key)) {
            return CACHE.get(key);
        }
        DistantMesh mesh = null;
        try {
            mesh = capture(info);
        } catch (Throwable e) {
            if (!errored) {
                errored = true;
                Logger.error("Distant bogey capture failed for " + key + " (logged once)", e);
            }
        }
        CACHE.put(key, mesh);
        return mesh;
    }

    private static DistantMesh capture(ShapeBogey info) {
        var style = AllBogeyStyles.BOGEY_STYLES.get(info.styleId());
        if (style == null) {
            return null;
        }
        BogeySizes.BogeySize size = null;
        for (BogeySizes.BogeySize candidate : style.validSizes()) {
            if (candidate.id().equals(info.sizeId())) {
                size = candidate;
                break;
            }
        }
        if (size == null) {
            var it = style.validSizes().iterator();
            if (!it.hasNext()) {
                return null;
            }
            size = it.next();
        }
        var builder = new DistantMeshBuilder();
        var capture = new CaptureBufferSource(builder);
        try {
            //BogeyStyle.render applies the -1.5078125 offset internally, so the snapshot bakes it in
            style.render(size, 0.0f, new PoseStack(), capture,
                    LightTexture.pack(0, 15), OverlayTexture.NO_OVERLAY, 0.0f, info.data(), true);
            capture.flush();
        } catch (Throwable e) {
            //build() is the only path that frees the native buffer; free it before the exception
            //propagates to getOrCapture's catch so a broken style does not leak once per style.
            builder.discard();
            throw e;
        }
        return builder.build();
    }

    //MultiBufferSource + VertexConsumer capture: collects position/uv/normal per vertex and hands
    //complete vertices to the builder. Light is uniform-driven at draw time; shade derives from the
    //captured normal (matching vanilla's directional block shading).
    private static final class CaptureBufferSource implements MultiBufferSource, VertexConsumer {
        private final DistantMeshBuilder builder;
        private boolean pending;
        private float x, y, z, u, v;
        private float nx, ny, nz;

        CaptureBufferSource(DistantMeshBuilder builder) {
            this.builder = builder;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            return this;
        }

        void flush() {
            if (this.pending) {
                //Shade and face both derive from the captured normal. Face matters under shaders:
                //the pack's patch reconstructs the surface normal from it, and a constant DOWN(0)
                //made every bogey light as an unlit underside (rendered near-black).
                float shade;
                int face;
                if (this.ny > 0.6f) {
                    shade = 1.0f;
                    face = 1; //up
                } else if (this.ny < -0.6f) {
                    shade = 0.5f;
                    face = 0; //down
                } else if (java.lang.Math.abs(this.nz) > java.lang.Math.abs(this.nx)) {
                    shade = 0.8f;
                    face = this.nz > 0 ? 3 : 2; //south : north
                } else {
                    shade = 0.6f;
                    face = this.nx > 0 ? 5 : 4; //east : west
                }
                this.builder.rawVertex(this.x, this.y, this.z, this.u, this.v, 15, 0, shade, face);
                this.pending = false;
            }
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.flush();
            this.x = x;
            this.y = y;
            this.z = z;
            this.u = 0;
            this.v = 0;
            this.nx = 0;
            this.ny = 1;
            this.nz = 0;
            this.pending = true;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            this.nx = x;
            this.ny = y;
            this.nz = z;
            return this;
        }
    }
}
