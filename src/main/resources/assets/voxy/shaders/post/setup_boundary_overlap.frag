#version 450 core

layout(binding = 0) uniform sampler2D depthTex;
layout(location = 1) uniform vec2 scaleFactor;
layout(location = 2) uniform mat4 inverseVanillaMvp;
layout(location = 6) uniform float fadeStartDistance;
layout(location = 7) uniform float fadeEndDistance;

#import <voxy:util/depthutils.glsl>

in vec2 UV;

float boundaryDither(ivec2 pixelPos) {
    uint hash = uint(pixelPos.x) * 0x8da6b343u;
    hash ^= uint(pixelPos.y) * 0xd8163841u;
    hash ^= hash >> 16u;
    hash *= 0x7feb352du;
    hash ^= hash >> 15u;
    return float(hash & 0xffffu) / 65535.0f;
}

void main() {
    vec2 sourceUv = UV * scaleFactor;
    float vanillaDepth = texture(depthTex, sourceUv).r;
    if (vanillaDepth == FAR) {
        discard;
    }

    // sourceUv addresses a possibly larger Iris texture; UV is still the
    // normalized coordinate of the active viewport used by the projection.
    vec4 cameraRelative = inverseVanillaMvp
            * vec4(SCREEN2NDC(vec3(UV, vanillaDepth)), 1.0f);
    cameraRelative.xyz /= cameraRelative.w;

    // Choose exactly one owner from the visible vanilla surface. Keeping this
    // decision in screen space prevents discarded surface fragments from
    // revealing deeper LOD cave geometry through a cylindrical cut.
    float horizontalDistance = length(cameraRelative.xz);
    if (horizontalDistance <= fadeStartDistance) {
        discard;
    }
    if (horizontalDistance < fadeEndDistance) {
        float fade = smoothstep(fadeStartDistance, fadeEndDistance, horizontalDistance);
        if (boundaryDither(ivec2(gl_FragCoord.xy)) > fade) {
            discard;
        }
    }

    // Never retain vanilla depth in the reopened area. In particular, Iris can
    // encode source and Voxy depth differently; comparing them created the
    // large empty ring fixed by this pass.
    gl_FragDepth = FAR;
}
