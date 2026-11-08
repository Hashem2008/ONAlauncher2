// ONA Mobile Shaders - after the opaque world: the sky for every pixel nothing covers, the sun,
// moon and stars added on top, and ambient occlusion. NO_SKY (Nether, End) keeps the game's
// own background and only adds what was drawn into colortex2.
#include "/lib/settings.glsl"
#include "/lib/common.glsl"

/* RENDERTARGETS: 0 */
#ifdef WATER_REFRACTION
/* RENDERTARGETS: 0,3 */
#endif
/*
const int colortex3Format = RGBA8;
const bool colortex3Clear = false;
const int colortex2Format = RGBA8;
const bool colortex2Clear = true;
const vec4 colortex2ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
*/

uniform sampler2D colortex0;
uniform sampler2D colortex2;
uniform sampler2D depthtex0;
uniform mat4 gbufferProjection;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform vec3 sunPosition;
uniform vec3 upPosition;
uniform vec3 skyColor;
uniform vec3 fogColor;
uniform vec3 cameraPosition;
uniform float rainStrength;
uniform float frameTimeCounter;

varying vec2 texcoord;

#ifdef VOLUMETRIC_CLOUDS
#ifdef ONA_CLOUDS
// Clouds with a body, marched through a layer 260-340 blocks up: the flat clouds' cover says where
// (the same noise, so cloud shadows still match), the height in the layer gives the shape: flat
// bottoms and round tops, the way cumulus look (the 2D-noise-with-height idea of BSL / Complementary)
const float CLOUD_BOTTOM = 260.0;
const float CLOUD_TOP = 340.0;

float onaCloudDensity(vec3 wp) {
    float h = (wp.y - CLOUD_BOTTOM) / (CLOUD_TOP - CLOUD_BOTTOM);
    vec2 p = wp.xz * 0.0025 + vec2(frameTimeCounter * 0.004, frameTimeCounter * 0.0015);
    float n = onaValueNoise(p) * 0.65 + onaValueNoise(p * 2.7) * 0.35;
    // small puffs that change with height, so the sides are not straight walls
    n += (onaValueNoise(p * 9.0 + h * 2.3) - 0.5) * 0.10;
    float shape = smoothstep(0.0, 0.12, h) * (1.0 - smoothstep(0.30, 1.0, h));
    float threshold = mix(0.98, 0.55 - rainStrength * 0.25, shape);
    return smoothstep(threshold, threshold + 0.10, n);
}

// rgb: cloud light reaching the eye (screen colours), a: how much of the sky behind still shows
vec4 onaVolumetricClouds(vec3 dirW, vec3 lightW, vec3 lit, float VdotS) {
    if (abs(dirW.y) < 0.0005) return vec4(0.0, 0.0, 0.0, 1.0);
    float tBottom = (CLOUD_BOTTOM - cameraPosition.y) / dirW.y;
    float tTop = (CLOUD_TOP - cameraPosition.y) / dirW.y;
    float t0 = max(min(tBottom, tTop), 0.0);
    float t1 = max(tBottom, tTop);
    if (t1 <= 0.0 || t0 > 6000.0) return vec4(0.0, 0.0, 0.0, 1.0);
    t1 = min(t1, t0 + 500.0); // grazing rays: a limited march
    float stepLen = (t1 - t0) / float(CLOUD_STEPS);
    float t = t0 + stepLen * onaNoise(gl_FragCoord.xy);
    float trans = 1.0;
    vec3 light = vec3(0.0);
    // light scattered forward: bright edges when the sun is behind a cloud
    float forward = 1.0 + 1.5 * pow(max(VdotS, 0.0), 8.0);
    for (int i = 0; i < CLOUD_STEPS; i++) {
        vec3 wp = cameraPosition + dirW * t;
        float density = onaCloudDensity(wp);
        if (density > 0.01) {
            float h = clamp((wp.y - CLOUD_BOTTOM) / (CLOUD_TOP - CLOUD_BOTTOM), 0.0, 1.0);
            // how much cloud lies between this point and the sun: dark bellies, bright tops
            float direct = exp(-onaCloudDensity(wp + lightW * 22.0) * 2.2) * forward;
            vec3 c = min(lit * (0.42 + 0.30 * h + 0.55 * direct), vec3(1.25));
            float a = 1.0 - exp(-density * stepLen * 0.045);
            light += trans * a * c;
            trans *= 1.0 - a;
            if (trans < 0.03) break;
        }
        t += stepLen;
    }
    // far away the clouds melt into the haze of the horizon
    float fade = 1.0 - smoothstep(2500.0, 6000.0, t0);
    return vec4(light * fade, mix(1.0, trans, fade));
}
#endif
#endif

vec3 onaViewPos(vec2 uv, float depth) {
    vec4 p = gbufferProjectionInverse * vec4(vec3(uv, depth) * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

void main() {
    vec3 color = texture2D(colortex0, texcoord).rgb;
    float depth = texture2D(depthtex0, texcoord).r;
    if (depth >= 1.0) {
        vec3 objects = texture2D(colortex2, texcoord).rgb;
#ifndef NO_SKY
        vec3 dir = normalize(onaViewPos(texcoord, 1.0));
        vec3 sunDir = normalize(sunPosition);
        vec3 up = normalize(upPosition);
        vec3 sky = onaSky(dir, sunDir, up, skyColor, fogColor, rainStrength);
#ifdef ONA_CLOUDS
        vec3 dirW = mat3(gbufferModelViewInverse) * dir;
#ifdef VOLUMETRIC_CLOUDS
        {
            float sunUpV = dot(sunDir, up);
            vec3 litV = mix(vec3(0.35, 0.38, 0.45), vec3(1.0, 0.97, 0.92), smoothstep(-0.1, 0.25, sunUpV));
            litV = mix(litV, vec3(1.0, 0.62, 0.40), (1.0 - smoothstep(0.0, 0.35, abs(sunUpV))) * 0.6);
            litV *= 1.0 - rainStrength * 0.45;
            vec3 sunW = mat3(gbufferModelViewInverse) * sunDir;
            // the moon lights them at night, from the other side
            vec4 clouds = onaVolumetricClouds(dirW, sunUpV > 0.0 ? sunW : -sunW, litV, dot(dir, sunDir));
            sky = sky * clouds.a + clouds.rgb;
            objects *= clouds.a;
        }
#else
        // a cloud layer 300 blocks up, two octaves of noise drifting with time
        if (dirW.y > 0.02) {
            vec2 p = (cameraPosition.xz + dirW.xz * ((300.0 - cameraPosition.y) / dirW.y)) * 0.0025;
            p += vec2(frameTimeCounter * 0.004, frameTimeCounter * 0.0015);
            float n = onaValueNoise(p) * 0.65 + onaValueNoise(p * 2.7) * 0.35;
            float cover = smoothstep(0.55 - rainStrength * 0.25, 0.85, n) * smoothstep(0.02, 0.18, dirW.y);
            float sunUp = dot(sunDir, up);
            vec3 lit = mix(vec3(0.35, 0.38, 0.45), vec3(1.0, 0.97, 0.92), smoothstep(-0.1, 0.25, sunUp));
            lit = mix(lit, vec3(1.0, 0.62, 0.40), (1.0 - smoothstep(0.0, 0.35, abs(sunUp))) * 0.6);
            lit *= 1.0 - rainStrength * 0.45;
            sky = mix(sky, lit * (0.85 + 0.15 * n), cover * 0.9);
            objects *= 1.0 - cover;
        }
#endif
#endif
        color = sky + objects;
#else
        color += objects;
#endif
    }
#ifdef SSAO
    else if (depth > 0.56) {
        vec3 p = onaViewPos(texcoord, depth);
        // far away the corners are a pixel wide: no samples there (the costly part of the pass)
        if (p.z > -40.0) {
        vec3 n = normalize(cross(dFdx(p), dFdy(p)));
        vec3 helper = abs(n.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
        vec3 t = normalize(cross(helper, n));
        vec3 b = cross(n, t);
        float angle = onaNoise(gl_FragCoord.xy) * 6.2831853;
        float radius = 0.6;
        float occlusion = 0.0;
        for (int i = 0; i < SSAO_SAMPLES; i++) {
            float f = (float(i) + 0.5) / float(SSAO_SAMPLES);
            float a = angle + float(i) * 2.3999632;
            float r = sqrt(f);
            vec3 k = vec3(cos(a) * r, sin(a) * r, sqrt(max(1.0 - f, 0.0)) + 0.1) * (0.25 + 0.75 * f);
            vec3 s = p + (t * k.x + b * k.y + n * k.z) * radius;
            vec4 sp = gbufferProjection * vec4(s, 1.0);
            vec2 suv = sp.xy / sp.w * 0.5 + 0.5;
            if (suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0) continue;
            float sceneZ = onaViewPos(suv, texture2D(depthtex0, suv).r).z;
            float range = smoothstep(0.0, 1.0, radius / max(abs(p.z - sceneZ), 0.001));
            occlusion += (sceneZ >= s.z + 0.03 ? 1.0 : 0.0) * range;
        }
        // fades out before the cut at 40 blocks, so there is no line
        float fade = 1.0 - smoothstep(28.0, 40.0, -p.z);
        color *= mix(1.0, 1.0 - occlusion / float(SSAO_SAMPLES) * fade, 0.75);
        }
    }
#endif
    gl_FragData[0] = vec4(color, 1.0);
#ifdef WATER_REFRACTION
    // the opaque world as it is now, for the water to show it bent
    gl_FragData[1] = vec4(color, 1.0);
#endif
}
