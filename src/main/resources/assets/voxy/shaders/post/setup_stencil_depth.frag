#version 450 core

layout(binding = 0) uniform sampler2D depthTex;
layout(location = 1) uniform vec2 scaleFactor;
layout(location = 2) uniform mat4 inverseVanillaMvp;
layout(location = 6) uniform float lodBoundaryFadeStart;
layout(location = 7) uniform float lodBoundaryFadeEnd;
layout(location = 10) uniform int boundaryGuardPass;
layout(location = 11) uniform mat4 voxyMvp;

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

void main() {
    float vanillaDepth = texture(depthTex, UV * scaleFactor).r;
    if (vanillaDepth == FAR) {
        discard;
    }

    vec3 cameraRelativePosition = vec3(0.0);
    float horizontalDistance = 0.0;
    float lodCoverage = 0.0;
    float ditherValue = 1.0;
    if (lodBoundaryFadeEnd > lodBoundaryFadeStart) {
        vec4 cameraRelative = inverseVanillaMvp
                * vec4(SCREEN2NDC(vec3(UV, vanillaDepth)), 1.0);
        cameraRelative.xyz /= cameraRelative.w;
        cameraRelativePosition = cameraRelative.xyz;

        horizontalDistance = length(cameraRelativePosition.xz);
        if (horizontalDistance > lodBoundaryFadeStart) {
            lodCoverage = smoothstep(
                    lodBoundaryFadeStart, lodBoundaryFadeEnd, horizontalDistance);
            ditherValue = orderedDither8x8(ivec2(gl_FragCoord.xy));
        }
    }

    if (boundaryGuardPass != 0) {
        // Only the dither-selected part of the transition band receives a
        // conservative copy of the vanilla surface depth. This lets nearby LOD
        // replace it, while rejecting caves or missing LOD far behind it.
        if (horizontalDistance <= lodBoundaryFadeStart
                || horizontalDistance >= lodBoundaryFadeEnd
                || ditherValue >= lodCoverage) {
            discard;
        }

        float rayLength = max(length(cameraRelativePosition), 1.0);
        vec3 guardedPosition = cameraRelativePosition
                * (1.0 + min(2.0 / rayLength, 0.125));
        vec4 guardedClip = voxyMvp * vec4(guardedPosition, 1.0);
        float guardedDepth = NDC2SCREEN_DEPTH(guardedClip.z / guardedClip.w);
        gl_FragDepth = gl_DepthRange.diff * guardedDepth + gl_DepthRange.near;
        return;
    }

    if (lodBoundaryFadeEnd > lodBoundaryFadeStart) {
        if (horizontalDistance >= lodBoundaryFadeEnd
                || (horizontalDistance > lodBoundaryFadeStart
                && ditherValue < lodCoverage)) {
            // Leave stencil/depth cleared: LOD owns this pixel. A second guarded
            // pass restores only the transition pixels' conservative depth.
            discard;
        }
    }

    // Drawing replaces the cleared stencil with zero, keeping vanilla terrain.
    gl_FragDepth = NEAR;
}
