#version 120
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/light_fsh.glsl"
#include "/lib/shadow_fsh.glsl"

/* RENDERTARGETS: 0,1 */

uniform sampler2D texture;
uniform vec4 entityColor;
uniform vec3 sunPosition;
uniform vec3 upPosition;
uniform vec3 skyColor;
uniform vec3 fogColor;
uniform float rainStrength;
uniform float far;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 viewPos;
varying vec3 normal;
varying vec3 fogTint;

void main() {
    vec4 albedo = texture2D(texture, texcoord) * glcolor;
    if (albedo.a < 0.1) discard;
    albedo.rgb = mix(albedo.rgb, entityColor.rgb, entityColor.a);
    vec3 n = normalize(normal);
#ifdef SHADOWS
    float vis = (SHADER_DISTANCE == 0 || length(viewPos) < float(SHADER_DISTANCE) * 16.0) ? onaShadow(dot(n, lightDir)) : smoothstep(0.75, 0.97, lmcoord.y);
#else
    float vis = smoothstep(0.75, 0.97, lmcoord.y);
#endif
    vec3 color = onaTonemap(onaShade(albedo.rgb, n, onaLightmap(lmcoord, length(viewPos)), vis, 1.0) * exposure);
    float d = length(viewPos) / far;
    float fog = clamp((d - FOG_START) / (1.0 - FOG_START), 0.0, 1.0);
    if (isEyeInWater == 1) fog = clamp(length(viewPos) / 48.0, 0.0, 1.0);
    color = mix(color, fogTint, fog * fog);
#ifdef DISTANCE_HAZE
    // the air between the eye and far land takes over, and glows towards the sun like real haze
    float hazeAmount = (1.0 - exp(-length(viewPos) * (0.0035 + rainStrength * 0.008))) * 0.6;
    vec3 hazeColor = fogTint + onaTonemap(sunLight) * pow(max(dot(normalize(viewPos), lightDir), 0.0), 6.0) * 0.35 * (1.0 - rainStrength);
    color = mix(color, min(hazeColor, vec3(1.0)), hazeAmount);
#endif
    gl_FragData[0] = vec4(color, albedo.a);
    // surface info: not a mirror (a mob in front of wet ground must not take the ground's mirror)
    gl_FragData[1] = vec4(0.5, 0.5, 0.0, 1.0);
}
