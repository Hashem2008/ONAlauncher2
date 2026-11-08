// ONA Mobile Shaders - shadow lookup. shadowtex1 is compared in hardware (soft edges from every
// tap), shadowtex0 keeps the raw depth for the realistic filter's blocker search (the same set-up
// Photon uses; without translucent shadows both hold the same depth).
#ifdef SHADOWS
uniform sampler2DShadow shadowtex1;
#if SHADOW_FILTER == 4
uniform sampler2D shadowtex0;
uniform mat4 shadowProjection;
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
    float vis = shadow2D(shadowtex1, p).x;
#elif SHADOW_FILTER == 1
    float vis = shadow2D(shadowtex1, p + vec3(-0.5, -0.5, 0.0) * texel).x;
    vis += shadow2D(shadowtex1, p + vec3( 0.5, -0.5, 0.0) * texel).x;
    vis += shadow2D(shadowtex1, p + vec3(-0.5,  0.5, 0.0) * texel).x;
    vis += shadow2D(shadowtex1, p + vec3( 0.5,  0.5, 0.0) * texel).x;
    vis *= 0.25;
#elif SHADOW_FILTER == 2
    // the 4 outer taps first: when they agree the pixel is fully lit or fully in shadow (most of
    // the screen) and the other 4 are not needed
    float vis = shadow2D(shadowtex1, p + vec3(-1.0, -0.4, 0.0) * texel).x
              + shadow2D(shadowtex1, p + vec3( 1.0,  0.4, 0.0) * texel).x
              + shadow2D(shadowtex1, p + vec3(-0.4,  1.0, 0.0) * texel).x
              + shadow2D(shadowtex1, p + vec3( 0.4, -1.0, 0.0) * texel).x;
    if (vis > 0.01 && vis < 3.99) {
        vis += shadow2D(shadowtex1, p + vec3(-1.5,  1.2, 0.0) * texel).x;
        vis += shadow2D(shadowtex1, p + vec3( 1.5, -1.2, 0.0) * texel).x;
        vis += shadow2D(shadowtex1, p + vec3(-1.2, -1.5, 0.0) * texel).x;
        vis += shadow2D(shadowtex1, p + vec3( 1.2,  1.5, 0.0) * texel).x;
        vis *= 0.125;
    } else {
        vis *= 0.25;
    }
#else
    float radius = 1.8;
#if SHADOW_FILTER == 4
    // realistic: a shadow is sharp where the block touches the ground and softer the further away
    // the blocker is, because the sun is a disc and not a point. First the blockers around here:
    float searchAngle = onaNoise(gl_FragCoord.xy + 17.0) * 6.2831853;
    float blockerSum = 0.0;
    float blockers = 0.0;
    for (int i = 0; i < 6; i++) {
        float a = searchAngle + float(i) * 2.3999632;
        vec2 o = vec2(cos(a), sin(a)) * sqrt((float(i) + 0.5) / 6.0) * 7.0 * texel;
        float d = texture2D(shadowtex0, p.xy + o).r;
        if (d < p.z) {
            blockerSum += d;
            blockers += 1.0;
        }
    }
    // nothing around casts a shadow on this point: fully lit, no filtering needed
    if (blockers < 0.5) return 1.0;
    // blocks between the blocker and this point, and the blur a sun of half a degree gives there
    float gap = (p.z - blockerSum / blockers) * 2.0 / abs(shadowProjection[2][2]);
    float texelsPerBlock = float(shadowMapResolution) * shadowProjection[0][0] * 0.5 / distortion;
    radius = clamp(gap * 0.018 * texelsPerBlock, 0.7, 7.0);
#endif
    // 12 taps on a disc turned differently for every pixel: smooth penumbra, no banding. The first
    // 4 lie spread around the disc: when they agree the pixel is fully lit or fully in shadow
    float angle = onaNoise(gl_FragCoord.xy) * 6.2831853;
    mat2 rot = mat2(cos(angle), sin(angle), -sin(angle), cos(angle));
    float vis = 0.0;
    for (int i = 0; i < 4; i++) {
        float a = float(i) * 1.5707963;
        vis += shadow2D(shadowtex1, p + vec3(rot * vec2(cos(a), sin(a)) * radius * 0.85 * texel, 0.0)).x;
    }
    if (vis > 0.01 && vis < 3.99) {
        for (int i = 0; i < 8; i++) {
            float r = sqrt((float(i) + 0.5) / 8.0) * radius;
            float a = float(i) * 2.3999632 + 0.785;
            vis += shadow2D(shadowtex1, p + vec3(rot * vec2(cos(a), sin(a)) * r * texel, 0.0)).x;
        }
        vis /= 12.0;
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
