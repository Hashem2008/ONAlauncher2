#version 120
#include "/lib/settings.glsl"
#include "/lib/common.glsl"

attribute vec4 mc_Entity;
attribute vec2 mc_midTexCoord;

uniform mat4 shadowModelView;
uniform mat4 shadowModelViewInverse;
uniform vec3 cameraPosition;
uniform float frameTimeCounter;
uniform float rainStrength;

varying vec2 texcoord;
varying vec4 glcolor;

#ifdef SHADOW_ENTITIES
// mobs and players cast shadows (shadowEntities in shaders.properties)
#endif

void main() {
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    glcolor = gl_Color;
    vec4 pos = gl_ModelViewMatrix * gl_Vertex;
#ifdef WAVING_PLANTS
    float id = mc_Entity.x;
    bool topVertex = gl_MultiTexCoord0.t < mc_midTexCoord.t;
    if (id == 10001.0 || id == 10003.0 || (id == 10002.0 && topVertex)) {
        vec4 playerPos = shadowModelViewInverse * pos;
        vec3 w = playerPos.xyz + cameraPosition;
        float t = frameTimeCounter * (1.7 + rainStrength * 1.3);
        float amount = (id == 10001.0 ? 0.04 : 0.08) * WIND_STRENGTH * (1.0 + rainStrength);
        playerPos.x += sin(t + w.x * 0.63 + w.z * 0.41 + w.y * 0.31) * amount;
        playerPos.z += cos(t * 0.83 + w.z * 0.71 + w.x * 0.23) * amount * 0.7;
        pos = shadowModelView * playerPos;
    }
#endif
    gl_Position = gl_ProjectionMatrix * pos;
    gl_Position.xy = onaDistort(gl_Position.xy);
}
