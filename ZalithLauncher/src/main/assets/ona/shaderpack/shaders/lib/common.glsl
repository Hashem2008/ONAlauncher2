// ONA Mobile Shaders - shared code

// Shadow map warp: more detail near the player, where it is looked at
vec2 onaDistort(vec2 p) {
    float factor = length(p) * SHADOW_DISTORT + (1.0 - SHADOW_DISTORT);
    return p / factor;
}

// Narkowicz ACES fit: bright light rolls off softly instead of clipping to white
vec3 onaTonemap(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

float onaLuma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Sky colour for a view direction (view space), from the game's own sky / fog colours so biomes,
// rain and the Nether / End keep their look, plus a warm glow around the sun at dawn and dusk.
vec3 onaSky(vec3 dir, vec3 sunDir, vec3 upDir, vec3 skyC, vec3 fogC, float rain) {
    float up = dot(dir, upDir);
    float sunDot = max(dot(dir, sunDir), 0.0);
    float sunUp = dot(sunDir, upDir);
    float horizon = pow(1.0 - clamp(up, 0.0, 1.0), 3.0);
    vec3 zenith = skyC * 1.05;
    vec3 col = mix(zenith, fogC, horizon);
    float dusk = 1.0 - smoothstep(0.0, 0.35, abs(sunUp));
    vec3 glow = vec3(1.0, 0.45, 0.18) * dusk * (1.0 - rain);
    col += glow * pow(sunDot, 6.0) * 0.65 * (0.3 + horizon);
    col += vec3(1.0, 0.85, 0.6) * pow(sunDot, 48.0) * 0.35 * max(sunUp + 0.1, 0.0) * (1.0 - rain);
    if (up < 0.0) col = mix(col, fogC * 0.8, clamp(-up * 3.0, 0.0, 1.0));
    return col;
}

// labPBR normal map (R, G = normal x, y; B = ambient occlusion)
vec3 onaDecodeNormal(vec4 n) {
    vec2 xy = n.xy * 2.0 - 1.0;
    return vec3(xy, sqrt(clamp(1.0 - dot(xy, xy), 0.0, 1.0)));
}
