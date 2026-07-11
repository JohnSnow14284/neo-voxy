#version 450 core

layout(binding = 0) uniform sampler2D baselineDepth;
layout(binding = 1) uniform sampler2D renderedDepth;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float before = texelFetch(baselineDepth, pixel, 0).r;
    float after = texelFetch(renderedDepth, pixel, 0).r;

    // Both textures use the same storage format and baseline is a direct GPU copy, so unchanged
    // pixels compare exactly. Only depth written by the external renderer is committed.
    if (before == after) {
        discard;
    }

    gl_FragDepth = after;
}
