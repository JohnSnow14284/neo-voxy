// Local copy of the small fog-distance helper used by Voxy shaders.
//
// Fabric builds could import this from Sodium as <sodium:include/fog.glsl>,
// but on NeoForge the Sodium jar is loaded as a separate module and Voxy
// should not rely on resolving resources from that module through its own
// class loader. Keeping the helper local also avoids hard-coding Sodium's
// internal shader asset layout.

float getFragDistance(int fogShape, vec3 position) {
    // Minecraft's FogShape enum uses 0 for SPHERE and 1 for CYLINDER.
    // Sphere fog uses full 3D distance; cylindrical fog uses horizontal
    // distance clamped against vertical distance.
    if (fogShape == 0) {
        return length(position);
    }
    return max(length(position.xz), abs(position.y));
}
