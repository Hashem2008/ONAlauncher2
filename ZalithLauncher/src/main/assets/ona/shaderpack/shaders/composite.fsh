#version 120
#include "/lib/settings.glsl"
#include "/lib/common.glsl"

/* RENDERTARGETS: 0 */
/*
const bool colortex1Clear = true;
const vec4 colortex1ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
*/

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D depthtex0;
uniform mat4 gbufferProjection;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform float rainStrength;
uniform int isEyeInWater;
#ifdef WATER_REFRACTION
uniform sampler2D depthtex1;
uniform vec3 upPosition;
#endif
#ifdef FLOATING_DUST
uniform vec3 cameraPosition;
uniform float frameTimeCounter;
uniform ivec2 eyeBrightnessSmooth;
#endif
#ifdef VOLUMETRIC_LIGHT
uniform sampler2DShadow shadowtex0;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
#endif

varying vec2 texcoord;
varying vec3 sunLight;
varying vec3 skyLight;
varying vec3 lightDir;
varying float exposure;

vec3 onaViewPos(vec2 uv, float depth) {
    vec4 p = gbufferProjectionInverse * vec4(vec3(uv, depth) * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

vec2 onaScreen(vec3 view) {
    vec4 p = gbufferProjection * vec4(view, 1.0);
    return p.xy / p.w * 0.5 + 0.5;
}

void main() {
    vec3 color = texture2D(colortex0, texcoord).rgb;
    float depth = texture2D(depthtex0, texcoord).r;
    vec3 viewPos = onaViewPos(texcoord, depth);
#ifdef WATER_REFRACTION
    {
        // refraction the way Complementary does it: on water the picture is shifted by the waves,
        // taken only from spots that are water too, so nothing above the surface is pulled under it
        vec4 w = texture2D(colortex1, texcoord);
        if (w.a > 0.5 && (SHADER_DISTANCE == 0 || length(viewPos) < float(SHADER_DISTANCE) * 16.0)) {
            vec3 wn = normalize(w.rgb * 2.0 - 1.0);
            vec3 upV = normalize(upPosition);
            vec3 slope = wn - upV * dot(wn, upV);
            // the more water between the surface and the ground behind it, the stronger the bend
            float under = clamp(viewPos.z - onaViewPos(texcoord, texture2D(depthtex1, texcoord).r).z, 0.0, 4.0);
            vec2 rc = texcoord + slope.xy * under * 0.012 / max(-viewPos.z * 0.06, 1.0);
            if (texture2D(colortex1, rc).a > 0.5) color = texture2D(colortex0, rc).rgb;
        }
    }
#endif
#ifdef SSR
    vec4 info = texture2D(colortex1, texcoord);
    if (info.a > 0.5 && isEyeInWater == 0 && (SHADER_DISTANCE == 0 || length(viewPos) < float(SHADER_DISTANCE) * 16.0)) {
        // march the reflected ray through the depth buffer: the world mirrored in the water
        vec3 n = normalize(info.rgb * 2.0 - 1.0);
        vec3 v = normalize(viewPos);
        vec3 r = reflect(v, n);
        float fresnel = 0.02 + 0.98 * pow(1.0 - max(dot(n, -v), 0.0), 5.0);
        vec3 stepv = r * (0.25 + length(viewPos) * 0.035);
        vec3 p = viewPos;
        vec2 hitUv = vec2(-1.0);
        for (int i = 0; i < SSR_STEPS; i++) {
            p += stepv;
            vec2 uv = onaScreen(p);
            if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0 || p.z > -0.1) break;
            float sceneDepth = texture2D(depthtex0, uv).r;
            float sceneZ = onaViewPos(uv, sceneDepth).z;
            if (sceneZ > p.z && sceneZ - p.z < length(stepv) * 2.0 + 0.5) {
                // a few halving steps for a sharp edge
                vec3 a = p - stepv;
                vec3 b = p;
                for (int j = 0; j < 4; j++) {
                    vec3 m = (a + b) * 0.5;
                    vec2 muv = onaScreen(m);
                    if (onaViewPos(muv, texture2D(depthtex0, muv).r).z > m.z) b = m; else a = m;
                }
                hitUv = onaScreen(b);
                if (texture2D(depthtex0, hitUv).r >= 1.0) hitUv = vec2(-1.0); // the sky: keep the sky reflection
                break;
            }
            stepv *= 1.18;
        }
        if (hitUv.x >= 0.0) {
            vec2 edge = smoothstep(vec2(0.0), vec2(0.08), hitUv) * (1.0 - smoothstep(vec2(0.92), vec2(1.0), hitUv));
            color = mix(color, texture2D(colortex0, hitUv).rgb, clamp(fresnel * 1.3, 0.0, 0.85) * edge.x * edge.y);
        }
    }
#endif
#ifdef VOLUMETRIC_LIGHT
    // light shafts: walk from the eye through the air and count how much of it the sun reaches
    float dist = min(length(viewPos), 48.0);
    vec3 dirW = mat3(gbufferModelViewInverse) * normalize(viewPos);
    float dither = onaNoise(gl_FragCoord.xy);
    // the shadow projection is affine: the start and the direction once, then one step is an add
    vec3 vlStart = (shadowProjection * (shadowModelView * vec4(0.0, 0.0, 0.0, 1.0))).xyz;
    vec3 vlDir = (shadowProjection * (shadowModelView * vec4(dirW, 0.0))).xyz;
    float lit = 0.0;
    float mu = dot(normalize(viewPos), lightDir);
    // the shafts show towards the sun: a quarter of the steps elsewhere (the air there is even)
    int steps = mu > 0.15 ? VL_STEPS : (VL_STEPS / 4 > 2 ? VL_STEPS / 4 : 2); // GLSL 1.20 has no integer max()
    for (int i = 0; i < VL_STEPS; i++) {
        if (i >= steps) break;
        float t = (float(i) + dither) / float(steps);
        vec3 sc = vlStart + vlDir * (t * t * dist);
        float l = length(sc.xy);
        sc.xy /= l * SHADOW_DISTORT + (1.0 - SHADOW_DISTORT);
        lit += l < 1.0 ? shadow2D(shadowtex0, sc * 0.5 + 0.5).x : 1.0;
    }
    lit /= float(steps);
    float g = 0.6;
    float phase = (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * mu, 1.5) * 0.08 + 0.03;
    float density = (0.35 + rainStrength * 0.4) * (dist / 48.0);
    // the open sky only gets a little: shafts belong in the air between the trees, not over the sky
    if (depth >= 1.0) density *= 0.35;
    vec3 air = isEyeInWater == 1 ? vec3(0.10, 0.35, 0.50) : sunLight;
    color += air * lit * phase * density * 0.32;
#endif
#ifdef FLOATING_DUST
    {
        // particles: a speck of dust in some of the little cells of air around the eye, drifting
        // and wobbling; by day it only shows where the sun reaches it (like dust in sunbeams), at
        // night some of them are fireflies that glow by themselves
        vec3 dirD = mat3(gbufferModelViewInverse) * normalize(viewPos);
        float sceneDist = length(viewPos);
        float maxT = min(sceneDist, 14.0);
        float day = smoothstep(0.20, 0.50, onaLuma(sunLight));
        float outdoors = float(eyeBrightnessSmooth.y) / 240.0;
        vec3 drift = vec3(frameTimeCounter * 0.07, -frameTimeCounter * 0.05, frameTimeCounter * 0.04);
        vec3 origin = cameraPosition * 0.5 + drift; // cells of 2 blocks moving with the air
        float dd = onaNoise(gl_FragCoord.xy + 31.0);
        vec3 specks = vec3(0.0);
        vec3 lastCell = vec3(-99999.0);
        for (int i = 0; i < 12; i++) {
            float t = 0.6 + (float(i) + dd) * 1.1;
            if (t > maxT) break;
            vec3 cell = floor(origin + dirD * (t * 0.5));
            if (cell == lastCell) continue;
            lastCell = cell;
            float h = onaHash(cell.xz + cell.y * 17.31);
            if (h > 0.12) continue; // a speck in 1 cell out of 8
            vec3 h3 = vec3(h, onaHash(cell.xz * 1.7 + cell.y * 3.1), onaHash(cell.zx * 2.3 - cell.y * 5.7));
            vec3 mote = cell + 0.2 + h3 * 0.6 + sin(frameTimeCounter * vec3(0.7, 0.5, 0.9) + h3 * 6.28) * 0.1;
            // the nearest point of the view ray to the speck
            vec3 rel = mote - origin;
            float along = dot(rel, dirD);
            if (along <= 0.0 || along * 2.0 > sceneDist) continue; // behind the eye or behind a block
            float size = 0.006 + 0.003 * h3.y;
            float s = smoothstep(size, size * 0.2, length(rel - dirD * along));
            if (s <= 0.0) continue;
            float lit = 1.0;
#ifdef VOLUMETRIC_LIGHT
            vec3 sc = (shadowProjection * (shadowModelView * vec4((mote - drift) * 2.0 - cameraPosition, 1.0))).xyz;
            float l = length(sc.xy);
            sc.xy /= l * SHADOW_DISTORT + (1.0 - SHADOW_DISTORT);
            lit = l < 1.0 ? shadow2D(shadowtex0, sc * 0.5 + 0.5).x : 1.0;
#endif
            // dust lights up mostly when you look towards the sun through it
            vec3 sunDust = sunLight * lit * (0.15 + 2.0 * pow(max(dot(normalize(viewPos), lightDir), 0.0), 8.0));
            vec3 firefly = vec3(0.75, 1.0, 0.35) * (0.5 + 0.5 * sin(frameTimeCounter * 3.0 + h3.z * 30.0)) * step(h, 0.04) * 2.0;
            specks += s * mix(firefly, sunDust, day) * (1.0 - along * 2.0 / 14.0);
        }
        color += specks * outdoors * (isEyeInWater == 0 ? 1.0 : 0.0);
    }
#endif
    gl_FragData[0] = vec4(color, 1.0);
}
