package me.cortex.voxy.client.compat.create;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Predicate;

//CPU-side mesh assembly straight from BakedQuad assets into the distant vertex format. Nothing in
//this chain touches vanilla BufferBuilders, RenderTypes or anything a shader mod can mixin, which
//is the whole point: geometry bakes identically regardless of the shader environment.
public final class DistantMeshBuilder {
    private ByteBuffer buffer;
    private int vertexCount;
    private final Vector4f scratch = new Vector4f();
    private final org.joml.Vector3f normalScratch = new org.joml.Vector3f();
    private final RandomSource random = RandomSource.create(42);

    public DistantMeshBuilder() {
        this.buffer = MemoryUtil.memAlloc(64 * 1024);
    }

    //Raw vertex entry for captured geometry (e.g. bogey renderers streaming into a capture consumer)
    public void rawVertex(float x, float y, float z, float u, float v, int skyLight, int blockLight, float shade, int face) {
        this.rawVertex(x, y, z, u, v, skyLight, blockLight, shade, face, 0xFFFFFF);
    }

    public void rawVertex(float x, float y, float z, float u, float v, int skyLight, int blockLight, float shade, int face, int tintRgb) {
        this.ensure(DistantMesh.STRIDE);
        this.buffer.putFloat(x).putFloat(y).putFloat(z);
        this.buffer.putFloat(u).putFloat(v);
        this.buffer.put((byte) (blockLight * 16 + 8)).put((byte) (skyLight * 16 + 8));
        this.buffer.put((byte) (int) (Math.max(0, Math.min(1, shade)) * 255.0f));
        this.buffer.put((byte) face);
        this.buffer.put((byte) (tintRgb >> 16)).put((byte) (tintRgb >> 8)).put((byte) tintRgb).put((byte) 0xFF);
        this.vertexCount++;
    }

    //Emits every quad of a block model at the given block offset. faceHidden filters culled sides
    //(e.g. faces buried between adjacent carriage blocks).
    public void blockModel(BlockState state, BakedModel model, float ox, float oy, float oz,
                           int skyLight, int blockLight, Predicate<Direction> faceHidden) {
        this.blockModel(state, model, ox, oy, oz, skyLight, blockLight, faceHidden, 0xFFFFFF);
    }

    //tintRgb applies to quads that declare a tint index (grass tops, foliage); untinted quads stay white
    public void blockModel(BlockState state, BakedModel model, float ox, float oy, float oz,
                           int skyLight, int blockLight, Predicate<Direction> faceHidden, int tintRgb) {
        this.blockModel(state, model, ox, oy, oz, skyLight, blockLight, faceHidden, tintRgb,
                net.neoforged.neoforge.client.model.data.ModelData.EMPTY);
    }

    //modelData feeds connected-texture wrappers (casings, glass): with EMPTY they bake every block as
    //the disconnected default
    public void blockModel(BlockState state, BakedModel model, float ox, float oy, float oz,
                           int skyLight, int blockLight, Predicate<Direction> faceHidden, int tintRgb,
                           net.neoforged.neoforge.client.model.data.ModelData modelData) {
        for (Direction direction : Direction.values()) {
            if (faceHidden != null && faceHidden.test(direction)) {
                continue;
            }
            this.random.setSeed(42);
            for (BakedQuad quad : model.getQuads(state, direction, this.random, modelData, null)) {
                this.quad(null, quad, ox, oy, oz, skyLight, blockLight, quad.isTinted() ? tintRgb : 0xFFFFFF);
            }
        }
        this.random.setSeed(42);
        for (BakedQuad quad : model.getQuads(state, null, this.random, modelData, null)) {
            this.quad(null, quad, ox, oy, oz, skyLight, blockLight, quad.isTinted() ? tintRgb : 0xFFFFFF);
        }
    }

    //Emits every quad of a model under an arbitrary transform (bezier track segments etc.)
    public void transformedModel(BakedModel model, Matrix4f transform, int skyLight, int blockLight) {
        for (Direction direction : Direction.values()) {
            this.random.setSeed(42);
            for (BakedQuad quad : model.getQuads(null, direction, this.random)) {
                this.quad(transform, quad, 0, 0, 0, skyLight, blockLight, 0xFFFFFF);
            }
        }
        this.random.setSeed(42);
        for (BakedQuad quad : model.getQuads(null, null, this.random)) {
            this.quad(transform, quad, 0, 0, 0, skyLight, blockLight, 0xFFFFFF);
        }
    }

    public void quad(Matrix4f transform, BakedQuad quad, float ox, float oy, float oz, int skyLight, int blockLight) {
        this.quad(transform, quad, ox, oy, oz, skyLight, blockLight, 0xFFFFFF);
    }

    public void quad(Matrix4f transform, BakedQuad quad, float ox, float oy, float oz, int skyLight, int blockLight, int tintRgb) {
        this.ensure(4 * DistantMesh.STRIDE);
        int[] vertices = quad.getVertices();
        byte lightU = (byte) (blockLight * 16 + 8);
        byte lightV = (byte) (skyLight * 16 + 8);
        //Face and shade must follow the transform: the quad's own direction is model-local, and a
        //rotated segment (bezier track) whose top face still claims model-north gets lit as a wall
        //by shader packs (they rebuild the surface normal from the face) - angle-dependent glare.
        Direction lit = quad.getDirection();
        if (transform != null) {
            var n = this.normalScratch.set(lit.getStepX(), lit.getStepY(), lit.getStepZ());
            transform.transformDirection(n);
            lit = faceOf(n.x, n.y, n.z);
        }
        byte shade = (byte) (int) (shadeOf(lit) * 255.0f);
        byte face = (byte) lit.get3DDataValue();
        byte tr = (byte) (tintRgb >> 16), tg = (byte) (tintRgb >> 8), tb = (byte) tintRgb;
        for (int i = 0; i < 4; i++) {
            int base = i * 8;
            float x = Float.intBitsToFloat(vertices[base]);
            float y = Float.intBitsToFloat(vertices[base + 1]);
            float z = Float.intBitsToFloat(vertices[base + 2]);
            if (transform != null) {
                this.scratch.set(x, y, z, 1.0f);
                transform.transform(this.scratch);
                x = this.scratch.x;
                y = this.scratch.y;
                z = this.scratch.z;
            }
            this.buffer.putFloat(x + ox).putFloat(y + oy).putFloat(z + oz);
            this.buffer.putFloat(Float.intBitsToFloat(vertices[base + 4]));
            this.buffer.putFloat(Float.intBitsToFloat(vertices[base + 5]));
            this.buffer.put(lightU).put(lightV).put(shade).put(face);
            this.buffer.put(tr).put(tg).put(tb).put((byte) 0xFF);
        }
        this.vertexCount += 4;
    }

    private static float shadeOf(Direction direction) {
        return switch (direction) {
            case DOWN -> 0.5f;
            case UP -> 1.0f;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
    }

    private static Direction faceOf(float nx, float ny, float nz) {
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (ay >= ax && ay >= az) {
            return ny >= 0 ? Direction.UP : Direction.DOWN;
        }
        if (az >= ax) {
            return nz >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return nx >= 0 ? Direction.EAST : Direction.WEST;
    }

    private void ensure(int bytes) {
        if (this.buffer.remaining() < bytes) {
            this.buffer = MemoryUtil.memRealloc(this.buffer, Math.max(this.buffer.capacity() * 2, this.buffer.capacity() + bytes));
        }
    }

    public boolean isEmpty() {
        return this.vertexCount < 4;
    }

    //Uploads and frees the CPU buffer; returns null for empty meshes
    public DistantMesh build() {
        int quadCount = this.vertexCount / 4;
        if (quadCount == 0) {
            MemoryUtil.memFree(this.buffer);
            this.buffer = null;
            return null;
        }
        //Truncate any trailing partial quad from a capture stream
        this.buffer.flip();
        this.buffer.limit(quadCount * 4 * DistantMesh.STRIDE);
        var mesh = new DistantMesh(this.buffer, quadCount);
        MemoryUtil.memFree(this.buffer);
        this.buffer = null;
        return mesh;
    }

    //Frees the native buffer without building - for exception paths that abandon a partial bake, so
    //the memAlloc'd buffer (which only build() frees) does not leak on every failed rebake.
    public void discard() {
        if (this.buffer != null) {
            MemoryUtil.memFree(this.buffer);
            this.buffer = null;
        }
    }
}
