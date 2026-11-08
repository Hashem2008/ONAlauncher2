#version 120
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/light_fsh.glsl"
#include "/lib/shadow_fsh.glsl"

/* RENDERTARGETS: 0,1 */
/*
const int colortex1Format = RGBA8;
const bool colortex1Clear = true;
const vec4 colortex1ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
*/

uniform sampler2D texture;
#ifdef WATER_DEPTH
uniform sampler2D depthtex1;
#ifdef WATER_REFRACTION
uniform sampler2D colortex3; // the opaque world, saved by deferred before the water is drawn
#endif
uniform float near;
uniform float viewWidth;
uniform float viewHeight;
#endif
uniform mat4 gbufferModelView;
#if defined CLOUD_SHADOWS && defined ONA_CLOUDS
uniform mat4 gbufferModelViewInverse;
#endif
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
varying vec3 fogTint;

float onaWaveHeight(vec2 p, float t) {
    return sin(p.x * 1.10 + t * 1.30) * 0.50
         + sin(p.y * 0.90 - t * 1.10) * 0.50
         + sin((p.x + p.y) * 2.30 + t * 2.10) * 0.22
         + sin((p.x - p.y) * 3.10 - t * 2.70) * 0.12;
}

#ifdef WATER_DEPTH
float onaLinearDepth(float d) {
    return 2.0 * near * far / (far + near - (d * 2.0 - 1.0) * (far - near));
}
#endif

void main() {
    vec4 albedo = texture2D(texture, texcoord) * glcolor;
    if (albedo.a < 0.02) discard;
    vec3 n = normalize(normal);
    vec3 up = normalize(upPosition);
    bool water = isWater > 0.5;
#ifdef WATER_WAVES
    if (water && dot(n, up) > 0.5) {
        float t = frameTimeCounter * WATER_WAVE_SPEED;
        float h = onaWaveHeight(worldPos.xz, t);
        float hx = onaWaveHeight(worldPos.xz + vec2(0.08, 0.0), t);
        float hz = onaWaveHeight(worldPos.xz + vec2(0.0, 0.08), t);
        n = normalize(mat3(gbufferModelView) * normalize(vec3((h - hx) * 0.6 * WATER_WAVE_SIZE, 1.0, (h - hz) * 0.6 * WATER_WAVE_SIZE)));
    }
#endif
#ifdef RAIN_RIPPLES
    if (water && rainStrength > 0.01 && dot(n, up) > 0.5) {
        vec2 drops = onaRipples(worldPos.xz, frameTimeCounter) * rainStrength * smoothstep(0.85, 0.95, lmcoord.y);
        n = normalize(n + mat3(gbufferModelView) * vec3(drops.x, 0.0, drops.y));
    }
#endif
#ifdef SHADOWS
    float vis = onaShadow(dot(normalize(normal), lightDir));
#else
    float vis = smoothstep(0.75, 0.97, lmcoord.y);
#endif
#ifdef CLOUD_SHADOWS
#ifdef ONA_CLOUDS
    {
        vec3 sunW = mat3(gbufferModelViewInverse) * lightDir;
        if (sunW.y > 0.05) vis *= 1.0 - onaCloudCover(worldPos.xz + sunW.xz * ((300.0 - worldPos.y) / sunW.y), frameTimeCounter, rainStrength) * 0.8;
    }
#endif
#endif
    vec3 color;
    float alpha = albedo.a;
#ifdef WATER_REFRACTION
    vec3 behind = vec3(0.0);
#endif
    if (water) {
#ifdef WATER_REFRACTION
        // what lies under the surface, seen through the waves: the bend grows with the water in
        // between and shrinks with distance
        vec2 screenUv = gl_FragCoord.xy / vec2(viewWidth, viewHeight);
        float under = max(onaLinearDepth(texture2D(depthtex1, screenUv).r) - onaLinearDepth(gl_FragCoord.z), 0.0);
        vec2 bent = screenUv + (n.xy - normalize(normal).xy) * min(under, 4.0) * 0.02 / max(length(viewPos) * 0.08, 1.0);
        // a spot in front of the water (the shore, a boat) must not show up under it
        if (texture2D(depthtex1, bent).r < gl_FragCoord.z) bent = screenUv;
        behind = texture2D(colortex3, bent).rgb;
#endif
        vec3 tint = glcolor.rgb * vec3(0.55, 0.75, 0.85);
        color = onaShade(mix(albedo.rgb, tint, 0.5), n, onaLightmap(lmcoord, length(viewPos)), vis, 1.0);
        alpha = 0.62;
#ifdef WATER_DEPTH
        // how much water lies between the surface and the ground behind it
        float ground = onaLinearDepth(texture2D(depthtex1, gl_FragCoord.xy / vec2(viewWidth, viewHeight)).r);
        float depth = max(ground - onaLinearDepth(gl_FragCoord.z), 0.0);
        float deep = 1.0 - exp(-depth * 0.28);
        color = mix(color * 1.15, color * vec3(0.30, 0.45, 0.60), deep);
        alpha = mix(0.30, 0.92, deep);
#ifdef WATER_FOAM
        // foam along the shore, broken up and drifting
        float tf = frameTimeCounter * 0.5;
        float foamNoise = onaValueNoise(worldPos.xz * 1.8 + vec2(tf, -tf * 0.7));
        float shore = 1.0 - smoothstep(0.0, 0.9, depth);
        float foam = shore * smoothstep(0.45, 0.75, foamNoise + shore * 0.45);
        color = mix(color, onaShade(vec3(0.92), n, onaLightmap(lmcoord, length(viewPos)), vis, 1.0), foam * 0.85);
        alpha = mix(alpha, 1.0, foam * 0.85);
#endif
#endif
#ifdef WATER_REFLECTIONS
        vec3 V = normalize(-viewPos);
        float fresnel = 0.02 + 0.98 * pow(1.0 - max(dot(n, V), 0.0), 5.0);
        vec3 sky = onaUntonemap(onaSky(reflect(-V, n), normalize(sunPosition), up, skyColor, fogColor, rainStrength)) / exposure;
        float outside = lmcoord.y * lmcoord.y;
        color = mix(color, sky, clamp(fresnel * 1.2, 0.0, 0.85) * outside);
        alpha = mix(alpha, 1.0, fresnel * outside * 0.6);
        vec3 H = normalize(lightDir + V);
        color += sunLight * pow(max(dot(n, H), 0.0), 220.0) * 10.0 * vis * outside;
#endif
    } else {
        color = onaShade(albedo.rgb, n, onaLightmap(lmcoord, length(viewPos)), vis, 1.0);
    }
    color = onaTonemap(color * exposure);
    float d = length(viewPos) / far;
    float fog = clamp((d - FOG_START) / (1.0 - FOG_START), 0.0, 1.0);
    if (isEyeInWater == 1) fog = clamp(length(viewPos) / 48.0, 0.0, 1.0);
    color = mix(color, fogTint, fog * fog);
#ifdef DISTANCE_HAZE
    color = mix(color, fogTint, (1.0 - exp(-length(viewPos) * (0.0035 + rainStrength * 0.008))) * 0.6);
#endif
#ifdef WATER_REFRACTION
    if (water) {
        // drawn over the bent view instead of over the straight one
        color = mix(behind, color, alpha);
        alpha = 1.0;
    }
#endif
    gl_FragData[0] = vec4(color, alpha);
    // for the reflection pass: water normal (view space) and a water mask
    gl_FragData[1] = vec4(n * 0.5 + 0.5, water ? 1.0 : 0.0);
}
