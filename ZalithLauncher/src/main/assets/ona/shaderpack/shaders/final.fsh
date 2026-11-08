#version 120
#include "/lib/settings.glsl"
#include "/lib/common.glsl"

uniform sampler2D colortex0;
#ifdef GODRAYS
uniform sampler2D depthtex0;
#endif
#ifdef BLOOM
const bool colortex0MipmapEnabled = true;
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
#ifdef BLOOM
    // smaller mip levels are already blurred: two lookups instead of a blur pass
    vec3 b = texture2D(colortex0, texcoord, 3.0).rgb * 0.5 + texture2D(colortex0, texcoord, 5.0).rgb * 0.5;
    b = max(b - 0.55, 0.0) / 0.45;
    c += b * b * 0.35;
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
#if LOOK_QUALITY >= 1
    c *= EXPOSURE;
    c *= vec3(1.0 + WARMTH, 1.0, 1.0 - WARMTH);
    float l = onaLuma(c);
    c = mix(vec3(l), c, SATURATION);
    c = clamp(c, 0.0, 1.0);
    c = mix(c, c * c * (3.0 - 2.0 * c), 0.25); // a gentle contrast curve
    c = clamp((c - 0.5) * CONTRAST + 0.5, 0.0, 1.0);
#endif
#ifdef VIGNETTE_FX
    vec2 v = texcoord - 0.5;
    c *= 1.0 - dot(v, v) * 0.9;
#endif
#ifdef FILM_GRAIN
    c += (onaNoise(gl_FragCoord.xy + fract(frameTimeCounter) * 97.0) - 0.5) * 0.035;
#endif
    gl_FragData[0] = vec4(c, 1.0);
}
