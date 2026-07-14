#version 450 core

layout(binding = 0) uniform sampler2D depthTex;
layout(location = 1) uniform vec2 scaleFactor;
layout(location = 2) uniform mat4 inverseVanillaMvp;
layout(location = 6) uniform float fadeStartDistance;
layout(location = 7) uniform int preserveVanillaDepth;

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

    // Keep the original hard mask inside the circle. From the inner edge of
    // the ring outwards, preserve vanilla depth and reopen stencil so Voxy's
    // distance dither can blend the two terrain representations.
    if (length(cameraRelative.xz) < fadeStartDistance) {
        discard;
    }

    // Iris draws into the shader pack's existing colour targets, so retain its
    // terrain depth. The vanilla pipeline uses a private colour target and
    // leaves FAR here; the final depth-aware composite resolves the overlap
    // without sampling untouched colour from a previous frame.
    gl_FragDepth = preserveVanillaDepth != 0 ? vanillaDepth : FAR;
}
