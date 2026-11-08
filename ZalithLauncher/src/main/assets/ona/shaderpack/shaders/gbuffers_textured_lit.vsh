#version 120
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/light_vsh.glsl"
#include "/lib/shadow_vsh.glsl"

uniform mat4 gbufferModelViewInverse;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 viewPos;
varying vec3 normal;

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmcoord = clamp((gl_TextureMatrix[1] * gl_MultiTexCoord1).xy * 33.05 / 32.0 - 1.05 / 32.0, 0.0, 1.0);
    glcolor = gl_Color;
    normal = normalize(gl_NormalMatrix * gl_Normal);
    vec4 view = gl_ModelViewMatrix * gl_Vertex;
    viewPos = view.xyz;
    gl_Position = gl_ProjectionMatrix * view;
#ifdef SHADOWS
    onaShadowPos((gbufferModelViewInverse * view).xyz, mat3(gbufferModelViewInverse) * normal);
#endif
    onaLightColors();
}
