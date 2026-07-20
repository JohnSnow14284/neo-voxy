#version 460 core

layout(binding = 0) uniform sampler2D uAtlas;
layout(binding = 1) uniform sampler2D uLightMap;

layout(location = 0) in vec2 fUv;
layout(location = 1) in vec2 fLightUv;
layout(location = 2) in float fShade;
layout(location = 3) flat in uint fFace;
layout(location = 4) in vec4 fColor;

#ifdef PATCHED_SHADER
//Same contract as voxy's opaque LOD fragment shader: the shader-pack side (appended below by
//patchOpaqueShader) implements voxy_emitFragment and writes the full g-buffer.
struct VoxyFragmentParameters {
    vec4 sampledColour;
    vec2 tile;
    vec2 uv;
    uint face;
    uint modelId;
    vec2 lightMap;
    vec4 tinting;
    uint customId;
};

void voxy_emitFragment(VoxyFragmentParameters parameters);
#else
layout(location = 0) out vec4 outColour;
#endif

void main() {
    #ifdef SOLID_COLOR
    //The beacon LOD uses the exact vanilla-computed section colour as fColor. Its lightweight
    //opaque core must not depend on the animated texture's low-alpha edge texels.
    vec4 colour = vec4(1.0);
    #else
    vec4 colour = texture(uAtlas, fUv);
    if (colour.a < 0.1) {
        discard;
    }
    #endif
    #ifdef PATCHED_SHADER
    //Shader packs light voxy geometry themselves (directional sky light etc.); pre-multiplying the
    //vanilla face shade here double-darkens - visible as black undersides on floating track spans.
    //The tint rides the parameters' tinting slot, same as LOD terrain's biome colour.
    colour.a = 1.0;
    voxy_emitFragment(VoxyFragmentParameters(colour, vec2(0.0), fUv, fFace, 0u, fLightUv, fColor, 0u));
    #else
    vec3 light = texture(uLightMap, fLightUv).rgb;
    //Alpha is the LOD metadata byte SSAO decodes (face = bits 0-2, hasAO = bit 6): emit the real
    //face with hasAO clear. An opaque 1.0 here reads back as face=7, whose normal is vec3(0), and
    //BETTER_SSAO then normalize()s it - NaN-darkened mesh pixels.
    outColour = vec4(colour.rgb * fColor.rgb * light * fShade, float(fFace & 7u) / 255.0);
    #endif
}
