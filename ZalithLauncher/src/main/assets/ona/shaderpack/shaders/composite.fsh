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
#ifdef SSR
    vec4 info = texture2D(colortex1, texcoord);
    if (info.a > 0.5 && isEyeInWater == 0) {
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
    float lit = 0.0;
    float mu = dot(normalize(viewPos), lightDir);
    // the shafts show towards the sun: a quarter of the steps elsewhere (the air there is even)
    int steps = mu > 0.15 ? VL_STEPS : (VL_STEPS / 4 > 2 ? VL_STEPS / 4 : 2); // GLSL 1.20 has no integer max()
    for (int i = 0; i < VL_STEPS; i++) {
        if (i >= steps) break;
        float t = (float(i) + dither) / float(steps);
        vec3 wp = dirW * (t * t * dist);
        vec4 sp = shadowProjection * (shadowModelView * vec4(wp, 1.0));
        vec3 sc = sp.xyz / sp.w;
        float l = length(sc.xy);
        sc.xy /= l * SHADOW_DISTORT + (1.0 - SHADOW_DISTORT);
        lit += l < 1.0 ? shadow2D(shadowtex0, sc * 0.5 + 0.5).x : 1.0;
    }
    lit /= float(steps);
    float g = 0.65;
    float phase = (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * mu, 1.5) * 0.08 + 0.03;
    float density = (0.35 + rainStrength * 0.4) * (dist / 48.0);
    vec3 air = isEyeInWater == 1 ? vec3(0.10, 0.35, 0.50) : sunLight;
    color += air * lit * phase * density * 0.5;
#endif
    gl_FragData[0] = vec4(color, 1.0);
}
