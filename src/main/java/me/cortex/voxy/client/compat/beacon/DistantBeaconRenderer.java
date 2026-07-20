package me.cortex.voxy.client.compat.beacon;

import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.create.DistantMesh;
import me.cortex.voxy.client.compat.create.DistantMeshBuilder;
import me.cortex.voxy.client.compat.create.DistantShaders;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Matrix4f;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

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
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

/**
 * Lightweight LOD beacon beams. The client beacon has already resolved stained-glass blending into
 * exact ARGB beam sections; this class snapshots those sections while their chunk is loaded and
 * keeps a tiny static mesh after it unloads. There is no world scan and no per-frame mesh rebuild.
 */
public final class DistantBeaconRenderer implements LodPipelineHooks.Renderer {
    public static final DistantBeaconRenderer INSTANCE = new DistantBeaconRenderer();

    private static final double VANILLA_BEACON_DISTANCE = 256.0;
    private static final int MAX_CACHED_BEACONS = 2048;
    //Thinner than vanilla's close-range core: the LOD beam also scales with distance to remain
    //visible, so a 0.2 radius became visually oversized at long range.
    private static final float BASE_RADIUS = 0.13f;
    private static final double DISTANCE_SCALE_START = 160.0;
    private static boolean captureErrorLogged;

    private static final class CachedBeam {
        final int[] colors;
        final int[] heights;
        final int renderHeight;
        final DistantMesh mesh;

        CachedBeam(int[] colors, int[] heights, int renderHeight, DistantMesh mesh) {
            this.colors = colors;
            this.heights = heights;
            this.renderHeight = renderHeight;
            this.mesh = mesh;
        }

        boolean matches(int[] otherColors, int[] otherHeights) {
            return Arrays.equals(this.colors, otherColors) && Arrays.equals(this.heights, otherHeights);
        }

        void close() {
            this.mesh.free();
        }
    }

    //Access-order gives the cache a deterministic, bounded LRU eviction policy for very large worlds.
    private final LinkedHashMap<BlockPos, CachedBeam> beams = new LinkedHashMap<>(16, 0.75f, true);
    //Only currently/very recently loaded beacon BEs are checked during the once-per-second cleanup.
    private final Map<BlockPos, WeakReference<BeaconBlockEntity>> liveBeacons = new HashMap<>();
    private ResourceKey<Level> dimension;
    private long lastMaintenanceTick = Long.MIN_VALUE;

    private DistantBeaconRenderer() {
    }

    /** Called by the beacon tick mixin. Work is phase-distributed and only runs once per second per beacon. */
    public static void onBeaconTick(BeaconBlockEntity beacon) {
        Level level = beacon.getLevel();
        if (level == null || !level.isClientSide || !VoxyConfig.CONFIG.distantBeaconBeams) {
            return;
        }
        long phase = Math.floorMod(beacon.getBlockPos().asLong(), 20L);
        if (Math.floorMod(level.getGameTime(), 20L) != phase) {
            return;
        }
        try {
            INSTANCE.capture(beacon);
        } catch (RuntimeException exception) {
            //A malformed/resource-reload-time snapshot must not take the client down. Keep the last
            //known-good mesh and allow later ticks to retry, while avoiding log spam.
            if (!captureErrorLogged) {
                captureErrorLogged = true;
                Logger.error("Failed to cache a distant beacon beam (logged once)", exception);
            }
        }
    }

    /** Event-driven invalidation for a beacon that was actually broken/replaced (not chunk unload). */
    public static void onBlockStateChanged(BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState,
                                           net.minecraft.world.level.block.state.BlockState newState) {
        if (oldState.is(net.minecraft.world.level.block.Blocks.BEACON)
                && !newState.is(net.minecraft.world.level.block.Blocks.BEACON)) {
            BlockPos immutable = pos.immutable();
            INSTANCE.liveBeacons.remove(immutable);
            INSTANCE.remove(immutable);
        }
    }

    private void capture(BeaconBlockEntity beacon) {
        Level level = beacon.getLevel();
        if (level == null) {
            return;
        }
        this.ensureDimension(level.dimension());
        BlockPos pos = beacon.getBlockPos().immutable();
        this.liveBeacons.put(pos, new WeakReference<>(beacon));

        var sections = beacon.getBeamSections();
        if (sections.isEmpty()) {
            this.remove(pos);
            return;
        }

        int[] colors = new int[sections.size()];
        int[] heights = new int[sections.size()];
        for (int i = 0; i < sections.size(); i++) {
            colors[i] = sections.get(i).getColor();
            heights[i] = sections.get(i).getHeight();
        }

        CachedBeam old = this.beams.get(pos);
        if (old != null && old.matches(colors, heights)) {
            return;
        }

        DistantMesh mesh = buildMesh(colors, heights);
        if (mesh == null) {
            this.remove(pos);
            return;
        }
        int lastStart = 0;
        for (int i = 0; i < heights.length - 1; i++) {
            lastStart += heights[i];
        }
        CachedBeam replacement = new CachedBeam(colors, heights, lastStart + BeaconRenderer.MAX_RENDER_Y, mesh);
        CachedBeam replaced = this.beams.put(pos, replacement);
        if (replaced != null) {
            replaced.close();
        }
        this.trimCache();
    }

    private static DistantMesh buildMesh(int[] colors, int[] heights) {
        DistantMeshBuilder builder = new DistantMeshBuilder();
        try {
            int start = 0;
            for (int i = 0; i < colors.length; i++) {
                int renderedHeight = i == colors.length - 1 ? BeaconRenderer.MAX_RENDER_Y : heights[i];
                addCrossedSection(builder, start, start + renderedHeight, colors[i] & 0xFFFFFF);
                start += heights[i];
            }
            return builder.build();
        } catch (Throwable throwable) {
            builder.discard();
            throw throwable;
        }
    }

    //Two double-sided crossed planes are half the vertices of a box and remain readable from every
    //far viewing angle. The full-bright light values match the vanilla beacon renderer.
    private static void addCrossedSection(DistantMeshBuilder builder, int y0, int y1, int rgb) {
        float r = BASE_RADIUS;
        float v0 = y0;
        float v1 = y1;
        addQuad(builder, -r, y1, -r, 0, v1, -r, y0, -r, 0, v0,
                r, y0, r, 1, v0, r, y1, r, 1, v1, rgb);
        addQuad(builder, -r, y1, r, 0, v1, -r, y0, r, 0, v0,
                r, y0, -r, 1, v0, r, y1, -r, 1, v1, rgb);
    }

    private static void addQuad(DistantMeshBuilder builder,
                                float x0, float y0, float z0, float u0, float v0,
                                float x1, float y1, float z1, float u1, float v1,
                                float x2, float y2, float z2, float u2, float v2,
                                float x3, float y3, float z3, float u3, float v3, int rgb) {
        //UP metadata avoids directional darkening; the beam is emissive and vanilla uses full light.
        builder.rawVertex(x0, y0, z0, u0, v0, 15, 15, 1.0f, 1, rgb);
        builder.rawVertex(x1, y1, z1, u1, v1, 15, 15, 1.0f, 1, rgb);
        builder.rawVertex(x2, y2, z2, u2, v2, 15, 15, 1.0f, 1, rgb);
        builder.rawVertex(x3, y3, z3, u3, v3, 15, 15, 1.0f, 1, rgb);
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        this.ensureDimension(mc.level.dimension());
        long gameTime = mc.level.getGameTime();
        if (gameTime == this.lastMaintenanceTick || Math.floorMod(gameTime, 20L) != 0L) {
            return;
        }
        this.lastMaintenanceTick = gameTime;

        Iterator<Map.Entry<BlockPos, WeakReference<BeaconBlockEntity>>> iterator = this.liveBeacons.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            BeaconBlockEntity beacon = entry.getValue().get();
            if (beacon != null && !beacon.isRemoved() && beacon.getLevel() == mc.level) {
                continue;
            }
            BlockPos pos = entry.getKey();
            if (!mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                //The snapshot is deliberately retained for LOD rendering after chunk unload.
                iterator.remove();
                continue;
            }
            var current = mc.level.getBlockEntity(pos);
            if (current instanceof BeaconBlockEntity currentBeacon && !currentBeacon.isRemoved()) {
                entry.setValue(new WeakReference<>(currentBeacon));
            } else {
                iterator.remove();
                //Chunk unload can detach BEs before the client chunk table changes. Keep the last
                //resolved snapshot; actual block replacement/removal uses onBlockStateChanged.
            }
        }
    }

    @SubscribeEvent
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        this.clearAll();
    }

    @Override
    public void render(me.cortex.voxy.client.core.AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        VoxyConfig config = VoxyConfig.CONFIG;
        Minecraft mc = Minecraft.getInstance();
        if (!config.isRenderingEnabled() || !config.distantBeaconBeams || mc.level == null
                || this.beams.isEmpty() || !mc.level.dimension().equals(this.dimension)) {
            return;
        }

        pipeline.setupAndBindOpaque(viewport);
        //The BER advertises 256 blocks, but the containing render section can stop being submitted at
        //the user's chunk distance. Pull takeover one section inward; the ownership stencil prevents
        //double drawing wherever vanilla still owns the pixel.
        double handover = Math.min(VANILLA_BEACON_DISTANCE,
                Math.max(32.0, mc.options.getEffectiveRenderDistance() * 16.0 - 16.0));
        double minDistSq = handover * handover;
        double maxDist = config.createLodRadius();
        double maxDistSq = maxDist * maxDist;
        Matrix4f transform = new Matrix4f();
        boolean stateBound = false;

        try {
            for (var entry : this.beams.entrySet()) {
                BlockPos pos = entry.getKey();
                CachedBeam beam = entry.getValue();
                double dx = pos.getX() + 0.5 - viewport.cameraX;
                double dz = pos.getZ() + 0.5 - viewport.cameraZ;
                double horizontalDistSq = dx * dx + dz * dz;
                if (horizontalDistSq < minDistSq || horizontalDistSq > maxDistSq) {
                    continue;
                }

                double dy = pos.getY() - viewport.cameraY;
                float radiusScale = (float) Math.max(1.0, Math.sqrt(horizontalDistSq) / DISTANCE_SCALE_START);
                float radius = BASE_RADIUS * radiusScale;
                if (!viewport.frustum.testAab(
                        (float) dx - radius, (float) dy, (float) dz - radius,
                        (float) dx + radius, (float) dy + beam.renderHeight, (float) dz + radius)) {
                    continue;
                }

                if (!stateBound) {
                    DistantShaders.forBeaconPipeline(pipeline).bind();
                    DistantShaders.bindTextures();
                    glEnable(GL_DEPTH_TEST);
                    glDepthFunc(depthFunc);
                    glDepthMask(true);
                    glDisable(GL_CULL_FACE);
                    glEnable(GL_STENCIL_TEST);
                    //Never overwrite vanilla-owned pixels; LOD terrain and its depth can occlude the beam.
                    glStencilFunc(GL_EQUAL, 1, 0x1);
                    glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
                    stateBound = true;
                }

                transform.set(viewport.MVP)
                        .translate((float) dx, (float) dy, (float) dz)
                        .scale(radiusScale, 1.0f, radiusScale);
                DistantShaders.uploadTransform(transform);
                beam.mesh.draw();
            }
        } finally {
            if (stateBound) {
                glBindVertexArray(0);
                glUseProgram(0);
                glStencilFunc(GL_EQUAL, 1, 0x1);
                glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
            }
        }
    }

    private void ensureDimension(ResourceKey<Level> newDimension) {
        if (!newDimension.equals(this.dimension)) {
            this.clearAll();
            this.dimension = newDimension;
        }
    }

    private void remove(BlockPos pos) {
        CachedBeam beam = this.beams.remove(pos);
        if (beam != null) {
            beam.close();
        }
    }

    private void trimCache() {
        Iterator<Map.Entry<BlockPos, CachedBeam>> iterator = this.beams.entrySet().iterator();
        while (this.beams.size() > MAX_CACHED_BEACONS && iterator.hasNext()) {
            var eldest = iterator.next();
            eldest.getValue().close();
            this.liveBeacons.remove(eldest.getKey());
            iterator.remove();
        }
    }

    private void clearAll() {
        for (CachedBeam beam : this.beams.values()) {
            beam.close();
        }
        this.beams.clear();
        this.liveBeacons.clear();
        this.dimension = null;
        this.lastMaintenanceTick = Long.MIN_VALUE;
        captureErrorLogged = false;
    }
}
