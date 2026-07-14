#version 450 core

layout(binding = 0) uniform sampler2D depthTex;
layout(location = 1) uniform vec2 scaleFactor;
layout(location = 2) uniform mat4 inverseVanillaMvp;
layout(location = 6) uniform float circleRadius;

#import <voxy:util/depthutils.glsl>

in vec2 UV;

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

    // Hard ownership boundary: the visible surface is vanilla inside the
    // circle and Voxy outside it. The extra LOD chunk behind the inner side is
    // coverage-only and cannot take part in depth or colour calculations.
    float horizontalDistance = length(cameraRelative.xz);
    if (horizontalDistance < circleRadius) {
        discard;
    }

    // Never retain vanilla depth in the reopened area. In particular, Iris can
    // encode source and Voxy depth differently; comparing them created the
    // large empty ring fixed by this pass.
    gl_FragDepth = FAR;
}
