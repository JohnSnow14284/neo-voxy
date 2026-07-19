#version 450 core

layout(binding = 0) uniform sampler2D depthTex;
layout(location = 1) uniform vec2 scaleFactor;
// inverse(vanillaProjection * modelView) and Voxy's MVP. Vanilla-owned
// pixels keep the baseline's real reprojected depth protocol.
layout(location = 2) uniform mat4 invSrcProjection;
layout(location = 3) uniform mat4 dstProjection;
// xy: destination NDC -> window depth; zw: source window -> NDC depth.
layout(location = 4) uniform vec4 depthRemap;
layout(location = 6) uniform float lodBoundaryFadeStart;
layout(location = 7) uniform float lodBoundaryFadeEnd;
layout(location = 10) uniform int boundaryGuardPass;

#import <voxy:util/depthutils.glsl>

in vec2 UV;

const int BAYER_8X8[64] = int[](
     0, 48, 12, 60,  3, 51, 15, 63,
    32, 16, 44, 28, 35, 19, 47, 31,
     8, 56,  4, 52, 11, 59,  7, 55,
    40, 24, 36, 20, 43, 27, 39, 23,
     2, 50, 14, 62,  1, 49, 13, 61,
    34, 18, 46, 30, 33, 17, 45, 29,
    10, 58,  6, 54,  9, 57,  5, 53,
    42, 26, 38, 22, 41, 25, 37, 21
);

float orderedDither8x8(ivec2 pixel) {
    ivec2 p = pixel & ivec2(7);
    return (float(BAYER_8X8[p.y * 8 + p.x]) + 0.5) * (1.0 / 64.0);
}

float projectDepth(vec3 cameraRelativePosition) {
    vec4 clip = dstProjection * vec4(cameraRelativePosition, 1.0);
    float depth = (clip.z / clip.w) * depthRemap.x + depthRemap.y;
    return clamp(depth, 0.0, 1.0 - (2.0 / ((1 << 24) - 1)));
}

void main() {
    float sourceDepth = texture(depthTex, UV * scaleFactor).r;
    // Shader packs can leave sky depth very close to FAR without writing the
    // exact sentinel. Preserve the baseline tolerance to avoid masking sky.
    if (abs(sourceDepth - FAR) < 1e-5) {
        discard;
    }

    vec4 cameraRelative = invSrcProjection
            * vec4(UV * 2.0 - 1.0,
                   sourceDepth * depthRemap.z + depthRemap.w, 1.0);
    cameraRelative.xyz /= cameraRelative.w;

    float horizontalDistance = length(cameraRelative.xz);
    float lodCoverage = 0.0;
    float ditherValue = 1.0;
    bool fadeEnabled = lodBoundaryFadeEnd > lodBoundaryFadeStart;
    if (fadeEnabled && horizontalDistance > lodBoundaryFadeStart) {
        lodCoverage = smoothstep(lodBoundaryFadeStart,
                lodBoundaryFadeEnd, horizontalDistance);
        ditherValue = orderedDither8x8(ivec2(gl_FragCoord.xy));
    }

    if (boundaryGuardPass != 0) {
        if (!fadeEnabled
                || horizontalDistance <= lodBoundaryFadeStart
                || horizontalDistance >= lodBoundaryFadeEnd
                || ditherValue >= lodCoverage) {
            discard;
        }

        float rayLength = max(length(cameraRelative.xyz), 1.0);
        vec3 guardedPosition = cameraRelative.xyz
                * (1.0 + min(2.0 / rayLength, 0.125));
        gl_FragDepth = projectDepth(guardedPosition);
        return;
    }

    if (fadeEnabled
            && (horizontalDistance >= lodBoundaryFadeEnd
                || (horizontalDistance > lodBoundaryFadeStart
                    && ditherValue < lodCoverage))) {
        // Leave the cleared stencil/depth in place: LOD owns this pixel.
        discard;
    }

    // Vanilla-owned pixels retain their real reprojected surface depth. This
    // is required by the compatibility baseline's occlusion and hook passes.
    gl_FragDepth = projectDepth(cameraRelative.xyz);
}
