#version 120
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/light_fsh.glsl"
#include "/lib/shadow_fsh.glsl"

uniform sampler2D texture;
uniform mat4 gbufferModelView;
uniform vec3 sunPosition;
uniform vec3 upPosition;
uniform vec3 skyColor;
uniform vec3 fogColor;
uniform float rainStrength;
uniform float far;
uniform float frameTimeCounter;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 viewPos;
varying vec3 worldPos;
varying vec3 normal;
varying float isWater;

float onaWaveHeight(vec2 p, float t) {
    return sin(p.x * 1.10 + t * 1.30) * 0.50
         + sin(p.y * 0.90 - t * 1.10) * 0.50
         + sin((p.x + p.y) * 2.30 + t * 2.10) * 0.22
         + sin((p.x - p.y) * 3.10 - t * 2.70) * 0.12;
}

void main() {
    vec4 albedo = texture2D(texture, texcoord) * glcolor;
    if (albedo.a < 0.02) discard;
    vec3 n = normalize(normal);
    vec3 up = normalize(upPosition);
    bool water = isWater > 0.5;
#ifdef WATER_WAVES
    if (water && dot(n, up) > 0.5) {
        float t = frameTimeCounter;
        float h = onaWaveHeight(worldPos.xz, t);
        float hx = onaWaveHeight(worldPos.xz + vec2(0.08, 0.0), t);
        float hz = onaWaveHeight(worldPos.xz + vec2(0.0, 0.08), t);
        vec3 worldN = normalize(vec3((h - hx) * 0.6, 1.0, (h - hz) * 0.6));
        n = normalize(mat3(gbufferModelView) * worldN);
    }
#endif
#ifdef SHADOWS
    float vis = onaShadow(dot(normalize(normal), lightDir));
#else
    float vis = smoothstep(0.75, 0.97, lmcoord.y);
#endif
    vec3 color;
    float alpha = albedo.a;
    if (water) {
        // clearer, deeper looking water: a little of the texture, mostly tinted light
        vec3 tint = glcolor.rgb * vec3(0.55, 0.75, 0.85);
        color = onaShade(mix(albedo.rgb, tint, 0.5), n, lmcoord, vis, 1.0);
        alpha = 0.62;
#ifdef WATER_REFLECTIONS
        vec3 V = normalize(-viewPos);
        float fresnel = 0.02 + 0.98 * pow(1.0 - max(dot(n, V), 0.0), 5.0);
        vec3 r = reflect(-V, n);
        vec3 sky = onaSky(r, normalize(sunPosition), up, skyColor, fogColor, rainStrength);
        float outside = lmcoord.y * lmcoord.y;
        color = mix(color, sky, clamp(fresnel * 1.2, 0.0, 0.85) * outside);
        alpha = mix(alpha, 1.0, fresnel * outside * 0.6);
        vec3 H = normalize(lightDir + V);
        float glint = pow(max(dot(n, H), 0.0), 220.0) * 6.0;
        color += sunLight * glint * vis * outside;
#endif
    } else {
        color = onaShade(albedo.rgb, n, lmcoord, vis, 1.0);
    }
    color = onaTonemap(color);
    float d = length(viewPos) / far;
    float fog = clamp((d - FOG_START) / (1.0 - FOG_START), 0.0, 1.0);
    if (isEyeInWater == 1) fog = clamp(length(viewPos) / 48.0, 0.0, 1.0);
    vec3 fogTint = isEyeInWater == 1 ? fogColor * 0.9 : onaSky(normalize(viewPos), normalize(sunPosition), up, skyColor, fogColor, rainStrength);
    color = mix(color, fogTint, fog * fog);
    gl_FragData[0] = vec4(color, alpha);
}
