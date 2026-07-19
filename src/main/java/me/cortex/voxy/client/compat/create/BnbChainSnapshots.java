package me.cortex.voxy.client.compat.create;

import com.kipti.bnb.content.kinetics.cogwheel_chain.behaviour.CogwheelChainBehaviour;
import com.kipti.bnb.content.kinetics.cogwheel_chain.render.CogwheelChainRenderGeometryBuilder;
import com.kipti.bnb.content.kinetics.cogwheel_chain.types.CogwheelChainType;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;

//bits_n_bobs cogwheel chains for the kinetic snapshot: the chain path lives in an azimuth behaviour on
//the controlling cogwheel (getControlledChain is null on every other wheel, so one capture covers the
//whole loop with no double-draw). Geometry reproduces the renderer's far-mip pass - straight straps
//between segment endpoints, static texture window (atlas-safe, no V tiling), thin radius - laid out
//with the same transform chain and quad order. All bnb/azimuth types are confined to this class; the
//caller only touches it when the mod is loaded.
public final class BnbChainSnapshots {
    private BnbChainSnapshots() {}

    //9 floats per vertex, positions relative to the block-entity origin:
    //x,y,z, atlasU,atlasV, sky,block, shade, face
    public static float[] capture(ClientLevel level, KineticBlockEntity be) {
        if (!(be instanceof SmartBlockEntity smart)) {
            return null;
        }
        CogwheelChainBehaviour behaviour = smart.getBehaviour(CogwheelChainBehaviour.TYPE);
        if (behaviour == null) {
            return null;
        }
        var chain = behaviour.getControlledChain();
        if (chain == null) {
            return null; //not the controlling wheel - the controller's snapshot draws the loop
        }
        var type = chain.getChainType();
        CogwheelChainType.ChainRenderInfo info = type.getRenderType();

        var segments = CogwheelChainRenderGeometryBuilder.buildSegments(chain, Vec3.ZERO);
        if (segments.isEmpty()) {
            return null;
        }

        ResourceLocation texture = type.getRenderTexture();
        var sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(ResourceLocation.fromNamespaceAndPath(texture.getNamespace(),
                        texture.getPath().replace("textures/", "").replace(".png", "")));

        BlockPos origin = be.getBlockPos();
        var verts = new ArrayList<float[]>();
        var transform = new Matrix4f();
        var vertex = new Vector3f();

        for (var segment : segments) {
            Vec3 from = segment.from(), to = segment.to();
            Vec3 diff = to.subtract(from);
            float yaw = (float) Math.toDegrees(Mth.atan2(diff.x, diff.z));
            float pitch = (float) Math.toDegrees(Mth.atan2(diff.y, diff.multiply(1.0, 0.0, 1.0).length()));
            float length = (float) from.distanceTo(to) + 0.05f;

            //One above the endpoint cogwheels - their own cells are solid voxels and read 0
            int light1 = DistantLightSampler.sample(level,
                    (int) Math.floor(origin.getX() + 0.5 + from.x),
                    (int) Math.floor(origin.getY() + 0.5 + from.y) + 1,
                    (int) Math.floor(origin.getZ() + 0.5 + from.z));
            int light2 = DistantLightSampler.sample(level,
                    (int) Math.floor(origin.getX() + 0.5 + to.x),
                    (int) Math.floor(origin.getY() + 0.5 + to.y) + 1,
                    (int) Math.floor(origin.getZ() + 0.5 + to.z));
            int sky1 = DistantLightSampler.sky(light1), block1 = DistantLightSampler.block(light1);
            int sky2 = DistantLightSampler.sky(light2), block2 = DistantLightSampler.block(light2);

            //renderChain's far-branch transform chain, then renderChainFastButWithGaps' (0.5, 0, 0.5)
            transform.identity()
                    .translate((float) from.x, (float) from.y, (float) from.z)
                    .rotate((float) Math.toRadians(yaw), 0, 1, 0)
                    .rotate((float) Math.toRadians(90.0f - pitch), 1, 0, 0)
                    .rotate((float) Math.toRadians(45.0), 0, 1, 0)
                    .translate(0.0f, 0.475f, 0.0f)
                    .translate(-0.5f, -0.5f, -0.5f)
                    .translate(0.5f, 0.0f, 0.5f);

            if (info.getVertexShape() == CogwheelChainType.VertexShape.CROSS) {
                float radius = 0.0625f;
                float minU = sprite.getU(0.1875f), maxU = sprite.getU(0.25f);
                float minV = sprite.getV(0.0f), maxV = sprite.getV(0.0625f);
                quad(verts, transform, vertex, length, 0, radius, 0, -radius, minU, maxU, minV, maxV, sky1, block1, sky2, block2);
                quad(verts, transform, vertex, length, 0, -radius, 0, radius, minU, maxU, minV, maxV, sky1, block1, sky2, block2);
                quad(verts, transform, vertex, length, radius, 0, -radius, 0, minU, maxU, minV, maxV, sky1, block1, sky2, block2);
                quad(verts, transform, vertex, length, -radius, 0, radius, 0, minU, maxU, minV, maxV, sky1, block1, sky2, block2);
            } else {
                float w = info.getWidth() / 16.0f, h = info.getHeight() / 16.0f;
                float half = 0.0625f;
                float minV = sprite.getV(0.0f), maxV = sprite.getV(h);
                quad(verts, transform, vertex, length, -half, half, half, half,
                        sprite.getU(h), sprite.getU(h + w), minV, maxV, sky1, block1, sky2, block2);
                quad(verts, transform, vertex, length, -half, -half, -half, half,
                        sprite.getU(0.0f), sprite.getU(h), minV, maxV, sky1, block1, sky2, block2);
                quad(verts, transform, vertex, length, half, -half, -half, -half,
                        sprite.getU(h + w + h), sprite.getU(h + w + h + w), minV, maxV, sky1, block1, sky2, block2);
                quad(verts, transform, vertex, length, half, half, half, -half,
                        sprite.getU(h + w), sprite.getU(h + w + h), minV, maxV, sky1, block1, sky2, block2);
            }
        }
        if (verts.isEmpty()) {
            return null;
        }
        float[] out = new float[verts.size() * 9];
        for (int i = 0; i < verts.size(); i++) {
            System.arraycopy(verts.get(i), 0, out, i * 9, 9);
        }
        return out;
    }

    //renderQuad's vertex order: top(light2), bottom(light1), bottom, top
    private static void quad(ArrayList<float[]> verts, Matrix4f transform, Vector3f vertex, float length,
                             float x0, float z0, float x1, float z1,
                             float minU, float maxU, float minV, float maxV,
                             int sky1, int block1, int sky2, int block2) {
        emit(verts, transform, vertex, x0, length, z0, maxU, minV, sky2, block2);
        emit(verts, transform, vertex, x0, 0.0f, z0, maxU, maxV, sky1, block1);
        emit(verts, transform, vertex, x1, 0.0f, z1, minU, maxV, sky1, block1);
        emit(verts, transform, vertex, x1, length, z1, minU, minV, sky2, block2);
    }

    private static void emit(ArrayList<float[]> verts, Matrix4f transform, Vector3f vertex,
                             float x, float y, float z, float u, float v, int sky, int block) {
        vertex.set(x, y, z);
        transform.transformPosition(vertex);
        verts.add(new float[]{vertex.x, vertex.y, vertex.z, u, v, sky, block, 1.0f, 1});
    }
}
