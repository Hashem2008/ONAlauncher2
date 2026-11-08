#version 120
#include "/lib/settings.glsl"
#include "/lib/common.glsl"

/* RENDERTARGETS: 0 */
uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform mat4 gbufferProjection;
uniform mat4 gbufferProjectionInverse;

varying vec2 texcoord;

vec3 onaViewPos(vec2 uv, float depth) {
    vec4 p = gbufferProjectionInverse * vec4(vec3(uv, depth) * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

void main() {
    vec3 color = texture2D(colortex0, texcoord).rgb;
#ifdef SSAO
    float depth = texture2D(depthtex0, texcoord).r;
    if (depth < 1.0 && depth > 0.56) {
        vec3 p = onaViewPos(texcoord, depth);
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
            // hemisphere point, denser near the centre
            vec3 k = vec3(cos(a) * r, sin(a) * r, sqrt(max(1.0 - f, 0.0)) + 0.1) * (0.25 + 0.75 * f);
            vec3 s = p + (t * k.x + b * k.y + n * k.z) * radius;
            vec4 sp = gbufferProjection * vec4(s, 1.0);
            vec2 suv = sp.xy / sp.w * 0.5 + 0.5;
            if (suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0) continue;
            float sceneZ = onaViewPos(suv, texture2D(depthtex0, suv).r).z;
            float range = smoothstep(0.0, 1.0, radius / max(abs(p.z - sceneZ), 0.001));
            occlusion += (sceneZ >= s.z + 0.03 ? 1.0 : 0.0) * range;
        }
        float ao = 1.0 - occlusion / float(SSAO_SAMPLES);
        color *= mix(1.0, ao, 0.75);
    }
#endif
    gl_FragData[0] = vec4(color, 1.0);
}
