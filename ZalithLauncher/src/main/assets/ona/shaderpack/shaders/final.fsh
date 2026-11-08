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

varying vec2 texcoord;
varying vec2 sunScreen;
varying float sunAmount;
varying vec3 rayColor;

void main() {
    vec3 c = texture2D(colortex0, texcoord).rgb;
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
    float l = onaLuma(c);
    c = mix(vec3(l), c, SATURATION);
    c = clamp(c, 0.0, 1.0);
    c = mix(c, c * c * (3.0 - 2.0 * c), 0.25); // a gentle contrast curve
    gl_FragData[0] = vec4(c, 1.0);
}
