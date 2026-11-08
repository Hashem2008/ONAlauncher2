#version 120
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/light_fsh.glsl"
#include "/lib/shadow_fsh.glsl"

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
    float vis = onaShadow(dot(n, lightDir));
#else
    float vis = smoothstep(0.75, 0.97, lmcoord.y);
#endif
    vec3 lit = onaShade(albedo.rgb, n, onaLightmap(lmcoord, length(viewPos)), vis, 1.0);
    vec3 V = normalize(-viewPos);
    if (SHINE_AMOUNT > 0.0) {
        // glossy paint for cars, mobs, armour and items: a GGX highlight and a little sky
        float luma = onaLuma(albedo.rgb);
        float smoothness = 0.45 + 0.35 * luma;
        lit += sunLight * onaSpecular(n, V, lightDir, smoothness, vec3(0.06)) * vis * SHINE_AMOUNT * smoothstep(0.05, 0.45, lmcoord.y);
        float fresnel = 0.04 + 0.96 * pow(1.0 - max(dot(n, V), 0.0), 5.0);
        lit = mix(lit, onaSky(reflect(-V, n), normalize(sunPosition), normalize(upPosition), skyColor, fogColor, rainStrength), clamp(fresnel * 0.35 * SHINE_AMOUNT * lmcoord.y, 0.0, 0.5));
    }
    if (PARTS_GLOW > 0.0) {
        // very bright, coloured little parts (lamps, headlights, eyes) light up
        float hi = max(albedo.r, max(albedo.g, albedo.b));
        float glow = smoothstep(0.82, 0.97, hi);
        lit += albedo.rgb * glow * PARTS_GLOW * 1.6;
    }
    vec3 color = onaTonemap(lit * exposure);
    float d = length(viewPos) / far;
    float fog = clamp((d - FOG_START) / (1.0 - FOG_START), 0.0, 1.0);
    if (isEyeInWater == 1) fog = clamp(length(viewPos) / 48.0, 0.0, 1.0);
    color = mix(color, fogTint, fog * fog);
    gl_FragData[0] = vec4(color, albedo.a);
}
