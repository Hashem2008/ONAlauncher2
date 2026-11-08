// ONA Mobile Shaders - shadow lookup. Hardware filtering gives soft edges from each tap for free.
#ifdef SHADOWS
uniform sampler2DShadow shadowtex0;
varying vec4 shadowClip;

float onaShadow(float NdotL) {
    if (NdotL <= 0.0) return 0.0;
    vec3 p = shadowClip.xyz / shadowClip.w;
    float dist = length(p.xy);
    if (dist > 1.0) return 1.0;
    float factor = dist * SHADOW_DISTORT + (1.0 - SHADOW_DISTORT);
    p.xy /= factor;
    p = p * 0.5 + 0.5;
    p.z -= (0.00025 + 0.0009 * (1.0 - NdotL)) * factor * factor * (2048.0 / float(shadowMapResolution));
    float texel = 1.0 / float(shadowMapResolution);
#if SHADOW_FILTER == 0
    float vis = shadow2D(shadowtex0, p).x;
#elif SHADOW_FILTER == 1
    float vis = shadow2D(shadowtex0, p + vec3(-0.5, -0.5, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3( 0.5, -0.5, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3(-0.5,  0.5, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3( 0.5,  0.5, 0.0) * texel).x;
    vis *= 0.25;
#else
    float vis = shadow2D(shadowtex0, p + vec3(-1.0, -0.4, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3( 1.0,  0.4, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3(-0.4,  1.0, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3( 0.4, -1.0, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3(-1.5,  1.2, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3( 1.5, -1.2, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3(-1.2, -1.5, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3( 1.2,  1.5, 0.0) * texel).x;
    vis *= 0.125;
#endif
    // fade out at the edge of the shadow distance instead of a hard line
    return mix(vis, 1.0, smoothstep(0.85, 1.0, dist));
}
#endif
