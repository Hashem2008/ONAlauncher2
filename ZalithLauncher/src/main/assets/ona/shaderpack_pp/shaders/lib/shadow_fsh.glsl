// ONA Mobile Shaders - shadow lookup. shadowtex0 is compared in hardware: soft edges from every
// tap. Only compare lookups are used (raw shadow depth is not readable the same way on every phone).
#ifdef SHADOWS
uniform sampler2DShadow shadowtex0;
#if SHADOW_FILTER >= 4
uniform mat4 shadowProjection;
#endif
// lookups around the point to find how far the blockers are, and the taps of the soft edge after
// the first 4 (filter 5, ONA Shader ++: three times as many, the edge of a shadow is a smooth ramp)
#if SHADOW_FILTER == 5
#define ONA_SEARCH_TAPS 8
#define ONA_FILTER_TAPS 20
#else
#define ONA_SEARCH_TAPS 5
#define ONA_FILTER_TAPS 8
#endif
varying vec4 shadowClip;

// visibility of the sun / moon whatever way the face points (for light through leaves)
float onaShadowAny() {
    vec3 p = shadowClip.xyz / shadowClip.w;
    float dist = length(p.xy);
    if (dist > 1.0) return 1.0;
    float distortion = dist * SHADOW_DISTORT + (1.0 - SHADOW_DISTORT);
    p.xy /= distortion;
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
    // the 4 outer taps first: when they agree the pixel is fully lit or fully in shadow (most of
    // the screen) and the other 4 are not needed
    float vis = shadow2D(shadowtex0, p + vec3(-1.0, -0.4, 0.0) * texel).x
              + shadow2D(shadowtex0, p + vec3( 1.0,  0.4, 0.0) * texel).x
              + shadow2D(shadowtex0, p + vec3(-0.4,  1.0, 0.0) * texel).x
              + shadow2D(shadowtex0, p + vec3( 0.4, -1.0, 0.0) * texel).x;
    if (vis > 0.01 && vis < 3.99) {
        vis += shadow2D(shadowtex0, p + vec3(-1.5,  1.2, 0.0) * texel).x;
        vis += shadow2D(shadowtex0, p + vec3( 1.5, -1.2, 0.0) * texel).x;
        vis += shadow2D(shadowtex0, p + vec3(-1.2, -1.5, 0.0) * texel).x;
        vis += shadow2D(shadowtex0, p + vec3( 1.2,  1.5, 0.0) * texel).x;
        vis *= 0.125;
    } else {
        vis *= 0.25;
    }
#else
    float radius = 1.8;
#if SHADOW_FILTER >= 4
    // realistic: sharp where the block touches the ground, softer the further away the blocker is
    // (the sun is a disc, not a point). How far the blockers are, from compare lookups only: around
    // this point, how much is in shadow at all, and how much still is with the point moved 3 blocks
    // towards the sun (those blockers are further away than that)
    float searchAngle = onaFrameNoise(gl_FragCoord.xy + 17.0) * 6.2831853;
    float anyBlock = 0.0;
    for (int i = 0; i < ONA_SEARCH_TAPS; i++) {
        float a = searchAngle + float(i) * 2.3999632;
        vec2 o = vec2(cos(a), sin(a)) * sqrt((float(i) + 0.5) / float(ONA_SEARCH_TAPS)) * 6.0 * texel;
        anyBlock += 1.0 - shadow2D(shadowtex0, vec3(p.xy + o, p.z)).x;
    }
    // nothing around casts a shadow on this point: fully lit, no filtering needed
    if (anyBlock < 0.01) return 1.0;
    float lift = 1.5 * abs(shadowProjection[2][2]); // 3 blocks in shadow depth
    float farBlock = 0.0;
    for (int i = 0; i < ONA_SEARCH_TAPS; i++) {
        float a = searchAngle + float(i) * 2.3999632;
        vec2 o = vec2(cos(a), sin(a)) * sqrt((float(i) + 0.5) / float(ONA_SEARCH_TAPS)) * 6.0 * texel;
        farBlock += 1.0 - shadow2D(shadowtex0, vec3(p.xy + o, p.z - lift)).x;
    }
    float texelsPerBlock = float(shadowMapResolution) * shadowProjection[0][0] * 0.5 / distortion;
    radius = clamp(mix(0.03, 0.12, farBlock / anyBlock) * texelsPerBlock, 0.7, 7.0);
#endif
    // 12 taps (24 with filter 5) on a disc turned differently for every pixel: smooth penumbra, no banding. The first
    // 4 lie spread around the disc: when they agree the pixel is fully lit or fully in shadow
    float angle = onaFrameNoise(gl_FragCoord.xy) * 6.2831853;
    mat2 rot = mat2(cos(angle), sin(angle), -sin(angle), cos(angle));
    float vis = 0.0;
    for (int i = 0; i < 4; i++) {
        float a = float(i) * 1.5707963;
        vis += shadow2D(shadowtex0, p + vec3(rot * vec2(cos(a), sin(a)) * radius * 0.85 * texel, 0.0)).x;
    }
    if (vis > 0.01 && vis < 3.99) {
        for (int i = 0; i < ONA_FILTER_TAPS; i++) {
            float r = sqrt((float(i) + 0.5) / float(ONA_FILTER_TAPS)) * radius;
            float a = float(i) * 2.3999632 + 0.785;
            vis += shadow2D(shadowtex0, p + vec3(rot * vec2(cos(a), sin(a)) * r * texel, 0.0)).x;
        }
        vis /= float(4 + ONA_FILTER_TAPS);
    } else {
        vis *= 0.25;
    }
#endif
    // fade out at the edge of the shadow distance instead of a hard line
    return mix(vis, 1.0, smoothstep(0.85, 1.0, dist));
}

float onaShadow(float NdotL) {
    if (NdotL <= 0.0) return 0.0;
    return onaShadowAny();
}
#endif
