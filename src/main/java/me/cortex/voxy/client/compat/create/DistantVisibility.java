package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.client.core.rendering.Viewport;
import org.joml.Matrix4f;

//Frustum rejection for the distant Create renderers. They walked their whole snapshot table every frame
//and submitted a draw for anything inside the render radius, so machinery behind the camera cost exactly
//as much as machinery in front of it. That was invisible while the radius was small and stopped being so
//once it was corrected to cover the full LOD distance.
//
//Viewport.frustum is a JOML FrustumIntersection built from MVP, and MVP consumes camera-relative world
//coordinates - the same space these renderers already translate their transforms into. So the test takes
//world coordinates and subtracts the camera, with no involvement from the section/innerTranslation split
//that the GPU traversal path uses.
public final class DistantVisibility {
    private DistantVisibility() {}

    public static boolean isBoxVisible(Viewport<?> viewport,
                                       double minX, double minY, double minZ,
                                       double maxX, double maxY, double maxZ) {
        float x0 = (float) (minX - viewport.cameraX);
        float y0 = (float) (minY - viewport.cameraY);
        float z0 = (float) (minZ - viewport.cameraZ);
        float x1 = (float) (maxX - viewport.cameraX);
        float y1 = (float) (maxY - viewport.cameraY);
        float z1 = (float) (maxZ - viewport.cameraZ);
        return viewport.frustum.testAab(x0, y0, z0, x1, y1, z1);
    }

    //A snapshot's bounds are in contraption-local space and the pose can rotate them, so the eight
    //corners go through the transform and the extent is taken from the result. Cheaper than it looks -
    //this runs once per snapshot per frame, against a draw call it usually removes.
    public static boolean isTransformedBoxVisible(Viewport<?> viewport, Matrix4f local,
                                                  double originX, double originY, double originZ,
                                                  net.minecraft.world.phys.AABB localBounds) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        var corner = new org.joml.Vector3f();
        for (int i = 0; i < 8; i++) {
            corner.set(
                    (float) ((i & 1) == 0 ? localBounds.minX : localBounds.maxX),
                    (float) ((i & 2) == 0 ? localBounds.minY : localBounds.maxY),
                    (float) ((i & 4) == 0 ? localBounds.minZ : localBounds.maxZ));
            local.transformPosition(corner);
            minX = Math.min(minX, corner.x); maxX = Math.max(maxX, corner.x);
            minY = Math.min(minY, corner.y); maxY = Math.max(maxY, corner.y);
            minZ = Math.min(minZ, corner.z); maxZ = Math.max(maxZ, corner.z);
        }
        return isBoxVisible(viewport,
                originX + minX, originY + minY, originZ + minZ,
                originX + maxX, originY + maxY, originZ + maxZ);
    }
}
