// ONA Mobile Shaders - after the opaque world: the sky for every pixel nothing covers, the sun,
// moon and stars added on top, and ambient occlusion. NO_SKY (Nether, End) keeps the game's
// own background and only adds what was drawn into colortex2.
#include "/lib/settings.glsl"
#include "/lib/common.glsl"

/* RENDERTARGETS: 0 */
/*
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
// 3D-looking clouds without marching through them (so no grain, and cheap): the cloud layer is
// shaded like a body. Where the cover grows towards the sun the cloud is in its own shadow (dark
// bellies, bright sunlit edges), thick middles are darker, thin edges glow when the sun is behind,
// and a high thin layer drifts slower above for depth.
float onaCloudNoise(vec2 p) {
    return onaValueNoise(p) * 0.65 + onaValueNoise(p * 2.7) * 0.35;
}

// rgb: cloud light (screen colours), a: how much of the sky behind still shows
vec4 onaShadedClouds(vec3 dirW, vec3 lightW, vec3 lit, float VdotS) {
    if (dirW.y < 0.01) return vec4(0.0, 0.0, 0.0, 1.0);
    float horizon = smoothstep(0.01, 0.15, dirW.y);
    vec2 p = (cameraPosition.xz + dirW.xz * ((300.0 - cameraPosition.y) / dirW.y)) * 0.0025;
    p += vec2(frameTimeCounter * 0.004, frameTimeCounter * 0.0015);
    float n = onaCloudNoise(p);
    float thr = 0.55 - rainStrength * 0.25;
    float puff = onaValueNoise(p * 9.0 + frameTimeCounter * 0.01) - 0.5;
    float cover = smoothstep(thr, thr + 0.30, n + puff * 0.12) * horizon;
    vec3 color = vec3(0.0);
    float alpha = 0.0;
    if (cover > 0.003) {
        // more cloud towards the sun = this side is in the cloud's own shadow
        vec2 toSun = normalize(lightW.xz + vec2(0.0001)) * 0.035;
        float self = clamp((onaCloudNoise(p + toSun) - n) * 3.0 + 0.5, 0.0, 1.0);
        float thick = smoothstep(thr, thr + 0.45, n);
        float silver = pow(max(VdotS, 0.0), 6.0) * (1.0 - thick) * 1.5;
        color = lit * clamp(1.05 - 0.45 * self - 0.25 * thick + silver, 0.35, 1.6);
        alpha = cover * 0.95;
    }
    // high thin streaks, 600 blocks up
    vec2 q = (cameraPosition.xz + dirW.xz * ((600.0 - cameraPosition.y) / dirW.y)) * vec2(0.0009, 0.0030);
    float cirrus = smoothstep(0.62, 0.92, onaValueNoise(q + frameTimeCounter * 0.002)) * 0.35 * horizon * (1.0 - rainStrength);
    return vec4(color * alpha + lit * cirrus * (1.0 - alpha), (1.0 - alpha) * (1.0 - cirrus));
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
            vec4 clouds = onaShadedClouds(dirW, sunUpV > 0.0 ? sunW : -sunW, litV, dot(dir, sunDir));
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
        if (p.z > -40.0 && (SHADER_DISTANCE == 0 || -p.z < float(SHADER_DISTANCE) * 16.0)) {
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
}
