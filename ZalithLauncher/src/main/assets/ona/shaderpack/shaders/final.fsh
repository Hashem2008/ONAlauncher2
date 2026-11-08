#version 120
#include "/lib/settings.glsl"
#include "/lib/common.glsl"

uniform sampler2D colortex0;
#if defined GODRAYS || defined DEPTH_OF_FIELD || defined LENS_FLARE
uniform sampler2D depthtex0;
#endif
#if defined BLOOM || defined DEPTH_OF_FIELD
const bool colortex0MipmapEnabled = true;
#endif
#ifdef DEPTH_OF_FIELD
uniform float near;
uniform float far;
float onaLinear(float d) {
    return 2.0 * near * far / (far + near - (d * 2.0 - 1.0) * (far - near));
}
#endif
#ifdef LENS_FLARE
// one soft coloured ring of the flare
vec3 onaGhost(vec2 uv, vec2 pos, float size, vec3 tint, float aspect) {
    float d = length((uv - pos) * vec2(aspect, 1.0));
    return tint * (smoothstep(size, size * 0.55, d) * 0.6 + smoothstep(size * 1.05, size, d) * smoothstep(size * 0.8, size, d) * 0.6);
}
#endif
uniform float viewWidth;
uniform float viewHeight;
uniform float frameTimeCounter;

varying vec2 texcoord;
varying vec2 sunScreen;
varying float sunAmount;
varying vec3 rayColor;

void main() {
#ifdef CHROMATIC
    // colour fringes growing towards the edges
    vec2 off = (texcoord - 0.5) * 0.004;
    vec3 c = vec3(texture2D(colortex0, texcoord + off).r, texture2D(colortex0, texcoord).g, texture2D(colortex0, texcoord - off).b);
#else
    vec3 c = texture2D(colortex0, texcoord).rgb;
#endif
#ifdef DEPTH_OF_FIELD
    // camera focus on the middle of the screen; the hand and the sky stay as they are
    float depthHere = texture2D(depthtex0, texcoord).r;
    if (depthHere > 0.56 && depthHere < 1.0) {
        // focus on the nearest thing around the middle (the player in third person, not the sky)
        float fd = min(min(texture2D(depthtex0, vec2(0.5)).r, texture2D(depthtex0, vec2(0.5, 0.40)).r),
                       min(texture2D(depthtex0, vec2(0.5, 0.60)).r, min(texture2D(depthtex0, vec2(0.42, 0.5)).r, texture2D(depthtex0, vec2(0.58, 0.5)).r)));
        float focus = min(onaLinear(fd), 96.0);
        // a sharp zone around the focus, then softer and softer
        float blur = clamp((abs(onaLinear(depthHere) - focus) - focus * 0.25) / max(focus * 2.0, 8.0) * DOF_STRENGTH, 0.0, 1.0);
        float lod = blur * 2.5;
        vec2 px = vec2(1.0 / viewWidth, 1.0 / viewHeight) * exp2(lod) * 0.6;
        vec3 soft = (texture2D(colortex0, texcoord + px, lod).rgb + texture2D(colortex0, texcoord - px, lod).rgb
                   + texture2D(colortex0, texcoord + vec2(px.x, -px.y), lod).rgb + texture2D(colortex0, texcoord + vec2(-px.x, px.y), lod).rgb) * 0.25;
        c = mix(c, soft, blur);
    }
#endif
#ifdef BLOOM
    // smaller mip levels are already blurred: two lookups instead of a blur pass
    vec3 b = texture2D(colortex0, texcoord, 3.0).rgb * 0.5 + texture2D(colortex0, texcoord, 5.0).rgb * 0.5;
    b = max(b - 0.80, 0.0) / 0.20;
    c += b * b * 0.30;
#endif
#ifdef GODRAYS
    if (sunAmount > 0.0) {
        vec2 stepUv = (sunScreen - texcoord) / 9.0;
        vec2 p = texcoord;
        float rays = 0.0;
        for (int i = 0; i < 8; i++) {
            p += stepUv;
            rays += texture2D(depthtex0, p).r > 0.9999 ? 1.0 : 0.0;
        }
        rays /= 8.0;
        float fall = 1.0 - clamp(length((texcoord - sunScreen) * vec2(viewWidth / viewHeight, 1.0)), 0.0, 1.0);
        c += rayColor * rays * fall * fall * 0.30 * sunAmount;
    }
#endif
#ifdef LENS_FLARE
    if (sunAmount > 0.0 && all(greaterThan(sunScreen, vec2(0.0))) && all(lessThan(sunScreen, vec2(1.0)))) {
        // only while the sun itself is not hidden behind a block
        if (texture2D(depthtex0, sunScreen).r >= 1.0) {
            float aspect = viewWidth / viewHeight;
            vec2 axis = vec2(0.5) - sunScreen;
            vec3 flare = onaGhost(texcoord, sunScreen + axis * 0.55, 0.045, vec3(1.0, 0.70, 0.40), aspect)
                       + onaGhost(texcoord, sunScreen + axis * 1.05, 0.025, vec3(0.50, 0.85, 1.00), aspect)
                       + onaGhost(texcoord, sunScreen + axis * 1.45, 0.075, vec3(0.70, 0.55, 1.00), aspect)
                       + onaGhost(texcoord, sunScreen + axis * 2.05, 0.035, vec3(1.00, 0.85, 0.55), aspect);
            float center = 1.0 - smoothstep(0.0, 0.7, length(axis));
            c += flare * rayColor * sunAmount * (0.10 + 0.15 * center);
        }
    }
#endif
#if COLOR_GRADE == 1
    // film: warm light, teal shadows
    float lg = onaLuma(c);
    c = mix(c * vec3(0.93, 1.0, 1.06), c * vec3(1.07, 1.0, 0.91), smoothstep(0.15, 0.75, lg));
#elif COLOR_GRADE == 2
    c *= vec3(0.94, 1.0, 1.08);
#elif COLOR_GRADE == 3
    c = max(mix(vec3(onaLuma(c)), c, 1.22), 0.0);
#endif
    c *= EXPOSURE;
    c *= vec3(1.0 + WARMTH, 1.0, 1.0 - WARMTH);
    float l = onaLuma(c);
    c = mix(vec3(l), c, SATURATION);
    c = clamp(c, 0.0, 1.0);
    c = mix(c, c * c * (3.0 - 2.0 * c), 0.25); // a gentle contrast curve
    c = clamp((c - 0.5) * CONTRAST + 0.5, 0.0, 1.0);
#ifdef VIGNETTE_FX
    vec2 v = texcoord - 0.5;
    c *= 1.0 - dot(v, v) * 0.9;
#endif
#ifdef FILM_GRAIN
    c += (onaNoise(gl_FragCoord.xy + fract(frameTimeCounter) * 97.0) - 0.5) * 0.035;
#endif
    gl_FragData[0] = vec4(c, 1.0);
}
