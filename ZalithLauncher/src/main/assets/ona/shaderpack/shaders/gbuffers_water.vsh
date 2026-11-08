#version 120
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/light_vsh.glsl"
#include "/lib/shadow_vsh.glsl"

attribute vec4 mc_Entity;

uniform mat4 gbufferModelView;
uniform mat4 gbufferModelViewInverse;
uniform vec3 cameraPosition;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 viewPos;
varying vec3 worldPos;
varying vec3 normal;
varying float isWater;

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmcoord = clamp((gl_TextureMatrix[1] * gl_MultiTexCoord1).xy * 33.05 / 32.0 - 1.05 / 32.0, 0.0, 1.0);
    glcolor = gl_Color;
    isWater = mc_Entity.x == 10010.0 ? 1.0 : 0.0;
    normal = normalize(gl_NormalMatrix * gl_Normal);
    vec4 view = gl_ModelViewMatrix * gl_Vertex;
    vec4 playerPos = gbufferModelViewInverse * view;
    worldPos = playerPos.xyz + cameraPosition;
    viewPos = view.xyz;
    gl_Position = gl_ProjectionMatrix * view;
    onaLightColors();
#ifdef SHADOWS
    onaShadowPos(playerPos.xyz, mat3(gbufferModelViewInverse) * normal, dot(normal, lightDir));
#endif
}
