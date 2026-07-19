#version 330 core

layout(binding = 0) uniform sampler2D beforeDepthTex;
layout(binding = 1) uniform sampler2D afterDepthTex;

in vec2 UV;

void main() {
    float beforeDepth = texture(beforeDepthTex, UV).r;
    float afterDepth = texture(afterDepthTex, UV).r;

    if (afterDepth >= beforeDepth - (2.0 / ((1 << 24) - 1))) {
        discard;
    }

    gl_FragDepth = afterDepth;
}
