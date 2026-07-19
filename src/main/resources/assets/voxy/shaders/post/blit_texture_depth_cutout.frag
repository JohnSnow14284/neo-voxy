#version 450 core

layout(binding = 0) uniform sampler2D depthTex;
layout(location = 1) uniform mat4 invProjMat;
layout(location = 2) uniform mat4 projMat;

#ifdef EMIT_COLOUR
layout(binding = 3) uniform sampler2D colourTex;
#ifdef USE_ENV_FOG
layout(location = 4) uniform vec2 fogParams;//.x=fogStart,.y=fogEnd
layout(location = 5) uniform vec4 fogColor;
layout(location = 6) uniform int fogShape;
layout(location = 7) uniform float fogIntensity;
layout(location = 8) uniform float fogDensity;
layout(location = 9) uniform int linearFog;
#endif
#endif

#import <voxy:util/depthutils.glsl>
#import <voxy:util/fog.glsl>

//Sampled window depth <-> analytic ndc. Under default clip control rasterized window depth is
//0.5*ndc+0.5 for the source's [0,1] projection and the destination's [-1,1] projection alike;
//treating them as identical (the depthutils identity map) makes the two errors cancel only when
//the destination never depth-tests the result against real geometry - against actual vanilla
//depth the residue is a constant n/(f-n) too-far bias that grows to d^2/f blocks of lost range.
#ifdef WINDOW_HALF_NDC
#define SRC_WINDOW2NDC_DEPTH(d) ((d)*2.0f-1.0f)
#define DST_NDC2WINDOW_DEPTH(z) ((z)*0.5f+0.5f)
#else
#define SRC_WINDOW2NDC_DEPTH(d) (d)
#define DST_NDC2WINDOW_DEPTH(z) (z)
#endif

out vec4 colour;
in vec2 UV;

vec3 rev3d(vec3 clip) {
    vec4 view = invProjMat * vec4(clip.xy*2.0f-1.0f, SRC_WINDOW2NDC_DEPTH(clip.z), 1.0f);
    return view.xyz/view.w;
}

float projDepth(vec3 pos) {
    vec4 view = projMat * vec4(pos, 1);
    return view.z/view.w;
}

void main() {
    float depth = texture(depthTex, UV.xy).r;
    if (depth == 0.0f || depth == 1.0f) {
        discard;
    }

    vec3 point = rev3d(vec3(UV.xy, depth));
    depth = DST_NDC2WINDOW_DEPTH(projDepth(point));
    //Clamp in window space: stay one step inside FAR so the exact-1.0 "untouched" semantics of
    //the destination never collide with legitimately-far geometry
    depth = REDUCTION2(FAR+CLOSER_SIGN*(2.0f/((1<<24)-1)), depth);

    depth = gl_DepthRange.diff * depth + gl_DepthRange.near;

    gl_FragDepth = depth;

    #ifdef EMIT_COLOUR
    colour = texture(colourTex, UV.xy);
    if (colour.a == 0.0) {
        discard;
    }
    #ifdef USE_ENV_FOG
    if (fogIntensity > 0.0){
        float dist = getFragDistance(fogShape, point.xyz);
        float linearAmount = clamp((dist - fogParams.x) / max(fogParams.y - fogParams.x, 0.0001), 0.0, 1.0);
        float fogLerp = linearFog != 0
                ? linearAmount
                : smoothstep(0.0, 1.0, linearAmount);
        if (fogDensity > 0.0) fogLerp = (exp(fogDensity * fogLerp) - 1.0) / (exp(fogDensity) - 1.0);
        colour.rgb = mix(colour.rgb, fogColor.rgb, clamp(fogLerp * fogIntensity, 0.0, 1.0));
    }
    #endif
    #else
    colour = vec4(0);
    #endif

}
