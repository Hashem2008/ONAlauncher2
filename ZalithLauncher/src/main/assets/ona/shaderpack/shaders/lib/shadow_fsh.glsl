// ONA Mobile Shaders - shadow lookup. Hardware filtering gives soft edges from each tap.
#ifdef SHADOWS
uniform sampler2DShadow shadowtex0;
varying vec4 shadowClip;

// visibility of the sun / moon whatever way the face points (for light through leaves)
float onaShadowAny() {
    vec3 p = shadowClip.xyz / shadowClip.w;
    float dist = length(p.xy);
    if (dist > 1.0) return 1.0;
    p.xy /= dist * SHADOW_DISTORT + (1.0 - SHADOW_DISTORT);
    p = p * 0.5 + 0.5;
    p.z -= 0.00004;
    float texel = 1.0 / float(shadowMapResolution);
#if SHADOW_FILTER == 0
    float vis = shadow2D(shadowtex0, p).x;
#elif SHADOW_FILTER == 1
    float vis = shadow2D(shadowtex0, p + vec3(-0.5, -0.5, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3( 0.5, -0.5, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3(-0.5,  0.5, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3( 0.5,  0.5, 0.0) * texel).x;
    vis *= 0.25;
#elif SHADOW_FILTER == 2
    float vis = 0.0;
    vis += shadow2D(shadowtex0, p + vec3(-1.0, -0.4, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3( 1.0,  0.4, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3(-0.4,  1.0, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3( 0.4, -1.0, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3(-1.5,  1.2, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3( 1.5, -1.2, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3(-1.2, -1.5, 0.0) * texel).x;
    vis += shadow2D(shadowtex0, p + vec3( 1.2,  1.5, 0.0) * texel).x;
    vis *= 0.125;
#else
    // 12 taps on a disc turned differently for every pixel: smooth penumbra, no banding
    float angle = onaNoise(gl_FragCoord.xy) * 6.2831853;
    mat2 rot = mat2(cos(angle), sin(angle), -sin(angle), cos(angle));
    float vis = 0.0;
    for (int i = 0; i < 12; i++) {
        float r = sqrt((float(i) + 0.5) / 12.0) * 1.8;
        float a = float(i) * 2.3999632;
        vec2 o = rot * vec2(cos(a), sin(a)) * r;
        vis += shadow2D(shadowtex0, p + vec3(o * texel, 0.0)).x;
    }
    vis /= 12.0;
#endif
    // fade out at the edge of the shadow distance instead of a hard line
    return mix(vis, 1.0, smoothstep(0.85, 1.0, dist));
}

float onaShadow(float NdotL) {
    if (NdotL <= 0.0) return 0.0;
    return onaShadowAny();
}
#endif
