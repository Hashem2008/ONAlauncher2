#version 120
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/light_vsh.glsl"
#include "/lib/shadow_vsh.glsl"

attribute vec4 mc_Entity;
attribute vec2 mc_midTexCoord;
attribute vec4 at_tangent;

uniform mat4 gbufferModelView;
uniform mat4 gbufferModelViewInverse;
uniform vec3 cameraPosition;
uniform float frameTimeCounter;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 viewPos;
varying vec3 normal;
varying vec3 tangent;
varying vec3 binormal;
varying float tangentOk;
varying float blockId;

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmcoord = clamp((gl_TextureMatrix[1] * gl_MultiTexCoord1).xy * 33.05 / 32.0 - 1.05 / 32.0, 0.0, 1.0);
    glcolor = gl_Color;
    blockId = mc_Entity.x;
    normal = normalize(gl_NormalMatrix * gl_Normal);
    // some vertex formats come without tangents: then no normal mapping (a zero tangent made NaN = black)
    tangentOk = length(at_tangent.xyz) > 0.01 ? 1.0 : 0.0;
    tangent = tangentOk > 0.5 ? normalize(gl_NormalMatrix * at_tangent.xyz) : vec3(1.0, 0.0, 0.0);
    binormal = tangentOk > 0.5 ? normalize(cross(tangent, normal)) * (at_tangent.w < 0.0 ? -1.0 : 1.0) : vec3(0.0, 1.0, 0.0);

    vec4 playerPos = gbufferModelViewInverse * (gl_ModelViewMatrix * gl_Vertex);
#ifdef WAVING_PLANTS
    float id = mc_Entity.x;
    bool topVertex = gl_MultiTexCoord0.t < mc_midTexCoord.t;
    if (id == 10001.0 || id == 10003.0 || (id == 10002.0 && topVertex)) {
        // the same motion as shadow.vsh, so a leaf never shades itself
        vec3 w = playerPos.xyz + cameraPosition;
        float t = frameTimeCounter * 1.7;
        float amount = id == 10001.0 ? 0.04 : 0.08;
        playerPos.x += sin(t + w.x * 0.63 + w.z * 0.41 + w.y * 0.31) * amount;
        playerPos.z += cos(t * 0.83 + w.z * 0.71 + w.x * 0.23) * amount * 0.7;
    }
#endif
    vec4 view = gbufferModelView * playerPos;
    viewPos = view.xyz;
    gl_Position = gl_ProjectionMatrix * view;
    onaLightColors();
#ifdef SHADOWS
    onaShadowPos(playerPos.xyz, mat3(gbufferModelViewInverse) * normal, dot(normal, lightDir));
#endif
}
