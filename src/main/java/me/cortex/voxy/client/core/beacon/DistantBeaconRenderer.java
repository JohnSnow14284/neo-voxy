package me.cortex.voxy.client.core.beacon;

import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.create.DistantMesh;
import me.cortex.voxy.client.compat.create.DistantMeshBuilder;
import me.cortex.voxy.client.compat.create.DistantShaders;
import me.cortex.voxy.client.compat.create.DistantVisibility;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL11C.GL_EQUAL;
import static org.lwjgl.opengl.GL11C.GL_KEEP;
import static org.lwjgl.opengl.GL11C.GL_REPLACE;
import static org.lwjgl.opengl.GL11C.glStencilFunc;
import static org.lwjgl.opengl.GL11C.glStencilOp;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;

//Beacon beams out where vanilla's block entity renderer has long since stopped. Only the solid inner
//core: vanilla draws that with no transparency and a depth write, so it belongs in the opaque pass and
//needs no blending stage of its own. The outer glow is translucent and is not drawn here.
public final class DistantBeaconRenderer implements LodPipelineHooks.Renderer {
    //Vanilla's BeaconRenderer stops here, and it measures horizontally - getViewDistance is squared
    //against dx/dz only, so a beacon directly overhead is still drawn
    private static final double VANILLA_BEAM_RANGE = 256.0;
    private static final ResourceLocation BEAM_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/beacon_beam.png");

    private static final float CORE_RADIUS = 0.2f;

    public static int lastFrameBeamsDrawn;

    private final List<Built> built = new ArrayList<>();
    private long builtForFrame = -1;

    //topY is kept so the draw can frustum-test the beam: it is a tall thin column, and testing only its
    //base rejects it whenever the base is below the view while the visible part is not.
    private record Built(DistantMesh mesh, double x, double y, double z, double topY) {}

    @Override
    public void render(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.distantBeacons || !cfg.isRenderingEnabled()) {
            this.discard();
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            this.discard();
            return;
        }
        var engine = WorldIdentifier.ofEngineNullable(mc.level);
        if (engine == null) {
            this.discard();
            return;
        }

        this.rebuildIfStale(engine, viewport);
        if (this.built.isEmpty()) {
            lastFrameBeamsDrawn = 0;
            return;
        }

        pipeline.setupAndBindOpaque(viewport);
        LodPipelineHooks.renderStateGuarded(() -> this.draw(pipeline, viewport, depthFunc));
    }

    private void draw(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        var shader = DistantShaders.forPipeline(pipeline, false);
        if (shader == null) {
            return;
        }
        shader.bind();
        DistantShaders.bindTextures();
        //Over the atlas that bindTextures just put on unit 0: the beam has its own texture and the
        //shader only ever samples one 2D image, so swapping the binding is the whole difference
        glBindTextureUnit(0, Minecraft.getInstance().getTextureManager().getTexture(BEAM_TEXTURE).getId());

        //Tag these pixels the way the other distant renderers do, then hand the stencil back: the guard
        //restores neither stencil state nor the tag the LOD passes expect
        glStencilFunc(GL_ALWAYS, 3, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        try {
            var transform = new Matrix4f();
            int drawn = 0;
            //Handover decided per frame rather than per rebuild. Deciding it while building meant a beam
            //that vanilla had just stopped drawing did not exist on our side until the next rebuild, so
            //crossing the boundary outward left a gap for up to the rebuild interval. Everything in LOD
            //range is built; this is the only thing that decides who draws it.
            double vanillaRange = Math.min(VANILLA_BEAM_RANGE,
                    Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0);
            double vanillaRangeSq = vanillaRange * vanillaRange;
            lastVanillaRange = (int) vanillaRange;
            int vanillaOwned = 0;
            for (var beam : this.built) {
                double bdx = beam.x - viewport.cameraX, bdz = beam.z - viewport.cameraZ;
                if (bdx * bdx + bdz * bdz < vanillaRangeSq) {
                    vanillaOwned++;
                    continue;
                }
                //A beam is a 1024-block column, so its box is tall and thin
                if (!DistantVisibility.isBoxVisible(viewport,
                        beam.x - CORE_RADIUS, beam.y, beam.z - CORE_RADIUS,
                        beam.x + CORE_RADIUS, beam.topY, beam.z + CORE_RADIUS)) {
                    continue;
                }
                transform.set(viewport.MVP).translate(
                        (float) (beam.x - viewport.cameraX),
                        (float) (beam.y - viewport.cameraY),
                        (float) (beam.z - viewport.cameraZ));
                DistantShaders.uploadTransform(transform);
                beam.mesh.draw();
                drawn++;
            }
            lastFrameBeamsDrawn = drawn;
            lastVanillaOwned = vanillaOwned;
        } finally {
            glStencilFunc(GL_EQUAL, 1, 0x1);
            glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        }
    }

    //Solving walks the voxel store and baking uploads buffers, so it happens once per interval rather
    //than per frame. A beam only changes when the sections around it are re-ingested.
    private void rebuildIfStale(me.cortex.voxy.common.world.WorldEngine engine, Viewport<?> viewport) {
        long now = System.currentTimeMillis();
        if (this.builtForFrame != -1 && now - this.builtForFrame < 2000) {
            return;
        }
        this.builtForFrame = now;
        this.discard();

        double maxDist = VoxyConfig.CONFIG.createRenderDistance(VoxyConfig.CONFIG.distantBeaconMaxChunks);
        double maxDistSq = maxDist * maxDist;
        var mc = Minecraft.getInstance();
        int[] skipped = new int[3];
        try {
            engine.getBeaconIndex().forEach((bx, by, bz) -> {
                double dx = (bx + 0.5) - viewport.cameraX;
                double dz = (bz + 0.5) - viewport.cameraZ;
                double horizontalSq = dx * dx + dz * dz;
                if (horizontalSq > maxDistSq) {
                    skipped[1]++;
                    return;
                }
                var segments = BeaconBeamSolver.solve(engine, bx, by, bz);
                if (segments.isEmpty()) {
                    skipped[2]++;
                    return;
                }
                var mesh = bake(segments, by);
                if (mesh != null) {
                    double topY = by;
                    for (var seg : segments) {
                        topY = Math.max(topY, seg.yTop());
                    }
                    this.built.add(new Built(mesh, bx + 0.5, by, bz + 0.5, topY));
                }
            });
        } catch (Throwable t) {
            Logger.error("Building distant beacon beams", t);
            this.discard();
        }
        lastVanillaOwned = skipped[0];
        lastOutOfRange = skipped[1];
        lastNoSegments = skipped[2];
        lastBuiltCount = this.built.size();
    }

    //Four sides of a square column per segment, textured along Y the way vanilla's beam is
    private static DistantMesh bake(List<BeaconBeamSolver.Segment> segments, int beaconY) {
        var builder = new DistantMeshBuilder();
        for (var segment : segments) {
            float y0 = segment.yBottom() - beaconY;
            float y1 = segment.yTop() - beaconY;
            float r = CORE_RADIUS;
            //V follows world height so the texture scrolls with length rather than stretching
            float v0 = segment.yBottom();
            float v1 = segment.yTop();
            int rgb = segment.colorRgb();
            //Full sky light: the beam is its own light source and must not be shaded by where it sits
            side(builder, -r, y0, -r, r, y1, -r, v0, v1, rgb, 2);
            side(builder, r, y0, r, -r, y1, r, v0, v1, rgb, 3);
            side(builder, r, y0, -r, r, y1, r, v0, v1, rgb, 4);
            side(builder, -r, y0, r, -r, y1, -r, v0, v1, rgb, 5);
        }
        return builder.isEmpty() ? null : builder.build();
    }

    private static void side(DistantMeshBuilder builder, float x0, float y0, float z0,
                             float x1, float y1, float z1, float v0, float v1, int rgb, int face) {
        builder.rawVertex(x0, y0, z0, 0.0f, v0, 15, 15, 1.0f, face, rgb);
        builder.rawVertex(x1, y0, z1, 1.0f, v0, 15, 15, 1.0f, face, rgb);
        builder.rawVertex(x1, y1, z1, 1.0f, v1, 15, 15, 1.0f, face, rgb);
        builder.rawVertex(x0, y1, z0, 0.0f, v1, 15, 15, 1.0f, face, rgb);
    }

    private void discard() {
        for (var beam : this.built) {
            beam.mesh.free();
        }
        this.built.clear();
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        this.discard();
        this.builtForFrame = -1;
    }

    //Why a beacon is not drawn splits four ways and only one of them is a bug, so each rejection is
    //counted separately rather than leaving a zero to be guessed at
    public static volatile int lastVanillaOwned;
    public static volatile int lastOutOfRange;
    public static volatile int lastNoSegments;

    public static String debugDump() {
        return "distant beacons: enabled=" + VoxyConfig.CONFIG.distantBeacons
                + " built=" + lastBuiltCount
                + " drawnLastFrame=" + lastFrameBeamsDrawn
                + " handoverAt=" + lastVanillaRange
                + " (skipped: vanillaOwns=" + lastVanillaOwned
                + " outOfLodRange=" + lastOutOfRange
                + " emptyBeam=" + lastNoSegments + ")";
    }

    public static volatile int lastBuiltCount;
    public static volatile int lastVanillaRange;
}
