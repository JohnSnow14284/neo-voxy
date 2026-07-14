#version 460 core
//Use quad shuffling to compute fragment mip
//#extension GL_KHR_shader_subgroup_quad: enable
#ifdef USE_SINGLE_TRI
#define USE_NV_BARRY
#endif

#ifdef USE_NV_BARRY
#extension GL_NV_fragment_shader_barycentric: require
#endif

layout(binding = 0) uniform sampler2D blockModelAtlas;
layout(binding = 2) uniform sampler2D depthTex;

//#define DEBUG_RENDER

//TODO: need to fix when merged quads have discardAlpha set to false but they span multiple tiles
// however they are not a full block

layout(location = 0) in flat uvec4 interData;
#ifndef USE_NV_BARRY
layout(location = 1) in vec2 uv;
#endif
layout(location = 2) in vec3 relativeWorldPos;

#ifdef DEBUG_RENDER
layout(location = 7) in flat uint quadDebug;
#endif


#ifndef PATCHED_SHADER
layout(location = 0) out vec4 outColour;
#else

//Bind the model buffer and import the model system as we need it
#define MODEL_BUFFER_BINDING 3
#import <voxy:lod/block_model.glsl>

#endif

#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:lod/lighting.glsl>


#import <voxy:util/depthutils.glsl>


vec4 uint2vec4RGBA(uint colour) {
    return vec4((uvec4(colour)>>uvec4(24,16,8,0))&uvec4(0xFF))/255.0;
}

uint unpackAlpha8(float alpha) {
    return uint(round(clamp(alpha, 0.0, 1.0) * 255.0));
}

bool sampleTintMask(vec2 texturePos) {
    return (unpackAlpha8(textureLod(blockModelAtlas, texturePos, 0).a) & 1u) != 0u;
}

vec4 clearTintMaskFromColour(vec4 colour) {
    float alpha = float(unpackAlpha8(colour.a) & 0xFEu);
    colour.a = alpha / 255.0;
    return colour;
}

//bool useMipmaps() {
//    return (interData.x&2u)==0u;
//}

uint tintingState() {
    return (interData.x>>2)&3u;
}

bool useDiscard() {
    return (interData.x&1u)==1u;
}

bool useBalancedLeafCutout() {
    return ((interData.x >> 1u) & 1u) == 1u;
}

vec2 varyBalancedLeafUV(vec2 localUV, vec2 tile, out uint transform) {
    uvec2 tilePos = uvec2(max(tile, vec2(0.0f)));
    uint hash = interData.w >> 16u;
    hash ^= tilePos.x * 0x9e3779b9u;
    hash ^= tilePos.y * 0x85ebca6bu;
    hash ^= hash >> 16u;
    hash *= 0x7feb352du;
    hash ^= hash >> 15u;
    transform = hash & 7u;

    // Eight stable rotations/reflections preserve the resource-pack alpha
    // pattern while avoiding mirrored pairs and repeated symmetric canopies.
    if ((transform & 1u) != 0u) localUV = localUV.yx;
    if ((transform & 2u) != 0u) localUV.x = 1.0f - localUV.x;
    if ((transform & 4u) != 0u) localUV.y = 1.0f - localUV.y;
    return localUV;
}

uint getFace() {
    return (interData.x>>4)&7u;
}


uint getModelId() {
    return interData.x>>16;
}

vec2 getBaseUV() {
    uint face = getFace();
    uint modelId = interData.x>>16;
    vec2 modelUV = vec2(modelId&0xFFu, (modelId>>8)&0xFFu)*(1.0/(256.0));
    return modelUV + (vec2(face>>1, face&1u) * (1.0/(vec2(3.0, 2.0)*256.0)));
}


#ifdef PATCHED_SHADER
struct VoxyFragmentParameters {
    //TODO: pass in derivative data
    vec4 sampledColour;
    vec2 tile;
    vec2 uv;
    uint face;
    uint modelId;
    vec2 lightMap;
    vec4 tinting;
    uint customId;//Same as iris's modelId
    float lodBoundaryFade;
};

void voxy_emitFragment(VoxyFragmentParameters parameters);
#else

vec4 computeColour(vec2 texturePos, vec4 colour) {
    //Conditional tinting, TODO: FIXME: this is better but still not great, try encode data into the top bit of alpha so its per pixel

    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2;//Always tint if function == 2
    if (tintingFunction == 1) {//partial tint
        doTint = sampleTintMask(texturePos);
    }
    if (doTint) {
        colour *= uint2vec4RGBA(interData.z).yzwx;
    }
    return (colour * uint2vec4RGBA(interData.y)) + vec4(0,0,0,float(interData.w&0xFFu)/255);
}

#endif

float getLodBoundaryDistance() {
    // Quad positions are relative to the current 32-block base section. Remove
    // the camera's sub-section offset to keep the circle centred on the player
    // and moving continuously instead of snapping at section boundaries.
    vec2 cameraRelativePos = relativeWorldPos.xz - cameraSubPos.xz;
    return length(cameraRelativePos);
}

void main() {
    //vec2 uv = vec2(0);
    //Tile is the tile we are in
    vec2 tile;
    #ifdef USE_NV_BARRY
    #ifdef USE_SINGLE_TRI
    if (gl_BaryCoordNV.x>=0.5||gl_BaryCoordNV.y>=0.5) discard;
    vec2 uv = gl_BaryCoordNV.yx*(vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)+1)*2;
    #else
    vec2 uv = mix(gl_BaryCoordNV.yx, 1-gl_BaryCoordNV.xz, gl_PrimitiveID&1)*(vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)+1);
    #endif
    #endif

    uint leafTransform = 0u;
    vec2 localUV = modf(uv, tile);
    if (useBalancedLeafCutout()) {
        localUV = varyBalancedLeafUV(localUV, tile, leafTransform);
    }
    vec2 uv2 = localUV*(1.0/(vec2(3.0,2.0)*256.0));
    vec4 colour;
    vec2 texPos = uv2 + getBaseUV();
//This is deprecated, TODO: remove the non mip code path
    //if (useMipmaps())
    {
        vec2 uvSmol = uv*(1.0/(vec2(3.0,2.0)*256.0));
        vec2 dx = dFdx(uvSmol);//vec2(lDx, dDx);
        vec2 dy = dFdy(uvSmol);//vec2(lDy, dDy);
        if ((leafTransform & 1u) != 0u) {
            dx = dx.yx;
            dy = dy.yx;
        }
        if ((leafTransform & 2u) != 0u) {
            dx.x = -dx.x;
            dy.x = -dy.x;
        }
        if ((leafTransform & 4u) != 0u) {
            dx.y = -dx.y;
            dy.y = -dy.y;
        }
        colour = textureGrad(blockModelAtlas, texPos, dx, dy);
        colour = clearTintMaskFromColour(colour);
    }// else {
    //    colour = textureLod(blockModelAtlas, texPos, 0);
    //}

    //If we are in shaders and are a helper invocation, just exit, as it enables extra performance gains for small sized
    // fragments, we do this here after derivative computation
    //Trying it with all shaders
    //#ifdef PATCHED_SHADER
    #ifndef PATCHED_SHADER_ALLOW_DERIVATIVES
    if (gl_HelperInvocation) {
        return;
    }
    #endif
    //#endif

    if (any(notEqual(clamp(tile, vec2(0), vec2((interData.x>>8)&0xFu, (interData.x>>12)&0xFu)), tile))) {
        discard;
        return;
    }

    //Check the minimum bounding texture and ensure we are greater than it
    if (DEPTH_SCALAR_COMPARE(gl_FragCoord.z, texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r)) {
        discard;
        return;
    }


    //Also, small quad is really fking over the mipping level somehow
    #ifndef TRANSLUCENT
    float cutoutAlpha = useBalancedLeafCutout()
            ? colour.a
            : textureLod(blockModelAtlas, texPos, 0).a;
    float cutoutThreshold = useBalancedLeafCutout() ? 0.42f : 0.1f;
    colour.a = 1.0f;
    if (useDiscard() && cutoutAlpha <= cutoutThreshold) {
    #else
    if (textureLod(blockModelAtlas, texPos, 0).a == 0.0f) {
    #endif
        //This is stupidly stupidly bad for divergence
        //TODO: FIXME, basicly what this do is sample the exact pixel (no lod) for discarding, this stops mipmapping fucking it over
        #ifndef DEBUG_RENDER
        discard;
        return;
        #endif
    }

    #ifndef PATCHED_SHADER_ALLOW_DERIVATIVES
    if (gl_HelperInvocation) {
        return;
    }
    #endif

    float boundaryDistance = getLodBoundaryDistance();
    bool boundaryEnabled = lodBoundaryFadeEnd > lodBoundaryFadeStart;
    if (boundaryEnabled && boundaryDistance < lodBoundaryFadeEnd) {
        // LOD geometry is cached for one chunk inside the circle, but it must
        // never contribute colour or depth on the vanilla-owned side.
        discard;
        return;
    }
    float lodBoundaryFade = 1.0f;

    #ifndef PATCHED_SHADER
    colour = computeColour(texPos, colour);
    outColour = colour;

    #ifdef DEBUG_RENDER
    uint hash = quadDebug*1231421+123141;
    hash ^= hash>>16;
    hash = hash*1231421+123141;
    hash ^= hash>>16;
    hash = hash * 1827364925 + 123325621;
    outColour = vec4(float(hash&15u)/15, float((hash>>4)&15u)/15, float((hash>>8)&15u)/15, 0);
    #endif

    #else
    uint modelId = getModelId();
    BlockModel model = modelData[modelId];
    uint tintingFunction = tintingState();
    bool doTint = tintingFunction==2;//Always tint if function == 2
    if (tintingFunction==1) {//Partial tint
        doTint = sampleTintMask(texPos);
    }
    vec4 tint = vec4(1);
    if (doTint) {
        tint = uint2vec4RGBA(interData.z).yzwx;
    }

    uint face = getFace();
    face ^= uint((face&1u)!=uint(gl_FrontFacing!=((face>>1)!=0u)));
    voxy_emitFragment(VoxyFragmentParameters(colour, tile, texPos, face, modelId, getLightmapUv(interData.y), tint, model.customId, lodBoundaryFade));

    #endif
}



//#ifdef GL_KHR_shader_subgroup_quad
/*
uint hash = (uint(tile.x)*(1<<16))^uint(tile.y);
uint horiz = subgroupQuadSwapHorizontal(hash);
bool sameTile = horiz==hash;
uint sv = mix(uint(-1), hash, sameTile);
uint vert = subgroupQuadSwapVertical(sv);
sameTile = sameTile&&vert==hash;
mipBias = sameTile?0:-5.0;
*/
/*
vec2 uvSmol = uv*(1.0/(vec2(3.0,2.0)*256.0));
float lDx = subgroupQuadSwapHorizontal(uvSmol.x)-uvSmol.x;
float lDy = subgroupQuadSwapVertical(uvSmol.y)-uvSmol.y;
float dDx = subgroupQuadSwapDiagonal(lDx);
float dDy = subgroupQuadSwapDiagonal(lDy);
vec2 dx = vec2(lDx, dDx);
vec2 dy = vec2(lDy, dDy);
colour = textureGrad(blockModelAtlas, texPos, dx, dy);
*/
//#else
//colour = texture(blockModelAtlas, texPos);
//#endif

//Undefine the depth stuff
#import <voxy:util/depthutils.glsl>
