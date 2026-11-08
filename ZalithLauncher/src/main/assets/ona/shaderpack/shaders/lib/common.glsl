// ONA Mobile Shaders - shared code (ONA's own, GPL-3.0)

vec2 onaDistort(vec2 p) {
    float factor = length(p) * SHADOW_DISTORT + (1.0 - SHADOW_DISTORT);
    return p / factor;
}

// Narkowicz ACES fit: bright light rolls off softly instead of clipping to white
float onaLuma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Tone curve on the brightness only (extended Reinhard, white at 2.83), so colours keep their hue:
// sand stays sand-coloured in the noon sun instead of turning white like snow (a per-channel curve
// pushed every bright colour towards white). Sunlit surfaces land on their vanilla colour.
vec3 onaTonemap(vec3 x) {
    x *= 1.2;
    float l = onaLuma(x);
    vec3 c = x * ((1.0 + l * 0.125) / (1.0 + l));
    float m = max(c.r, max(c.g, c.b));
    if (m > 1.0) {
        // too bright for the screen: towards white, without a hue shift
        float over = m - 1.0;
        c = mix(c / m, vec3(1.0), over / (over + 1.0) * 0.6);
    }
    return clamp(c, 0.0, 1.0);
}

// The way back, for colours that are already on screen terms (the sky) mixed before the tone curve
vec3 onaUntonemap(vec3 c) {
    float l = clamp(onaLuma(c), 1e-4, 0.97);
    float y = (sqrt((1.0 - l) * (1.0 - l) + l * 0.5) - (1.0 - l)) * 4.0;
    return c * (y / l) / 1.2;
}

// interleaved gradient noise: a different offset per pixel, no texture needed
float onaNoise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

// Sky made of a blue zenith, a pale horizon that turns orange on the sun's side at dawn and dusk,
// a forward-scattering halo around the sun and a dark blue night (colours tuned on a CPU render).
vec3 onaAtmosphere(vec3 dir, vec3 sunDir, vec3 upDir, float rain) {
    float mu = clamp(dot(dir, sunDir), -1.0, 1.0);
    float h = clamp(dot(dir, upDir), 0.0, 1.0);
    float sunUp = dot(sunDir, upDir);
    float day = smoothstep(-0.10, 0.25, sunUp);
    float dusk = 1.0 - smoothstep(0.0, 0.45, abs(sunUp));
    float night = 1.0 - smoothstep(-0.25, 0.0, sunUp);
    vec3 zenith = mix(vec3(0.27, 0.49, 0.95), vec3(0.20, 0.24, 0.50), dusk) * (0.35 + 0.65 * day);
    zenith = mix(zenith, vec3(0.010, 0.016, 0.035), night);
    float toward = (mu * 0.5 + 0.5) * (mu * 0.5 + 0.5);
    vec3 horizon = mix(vec3(0.72, 0.84, 1.00) * (0.35 + 0.65 * day),
                       mix(vec3(0.45, 0.32, 0.38), vec3(1.00, 0.52, 0.24), toward), dusk);
    horizon = mix(horizon, vec3(0.015, 0.020, 0.040), night);
    float k = pow(1.0 - h, 4.0);
    vec3 col = mix(zenith, horizon, k);
    float g = 0.76;
    float mie = (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * mu, 1.5);
    vec3 sunCol = mix(vec3(1.00, 0.95, 0.85), vec3(1.00, 0.55, 0.25), dusk);
    col += sunCol * min(mie, 18.0) * 0.006 * SUN_HALO * (0.4 + 0.6 * day) * (1.0 - night);
    col = mix(col, vec3(onaLuma(col)) * vec3(0.95, 0.97, 1.0), rain * 0.75);
    return col * 1.15;
}

// Sky colour for a view direction. The game's own sky and fog colours keep biomes, rain and the
// Nether / End looking right; ATMOSPHERE blends the scattering sky over them.
vec3 onaSky(vec3 dir, vec3 sunDir, vec3 upDir, vec3 skyC, vec3 fogC, float rain) {
    float up = dot(dir, upDir);
    float sunDot = max(dot(dir, sunDir), 0.0);
    float sunUp = dot(sunDir, upDir);
    float horizon = pow(1.0 - clamp(up, 0.0, 1.0), 3.0);
    vec3 col = mix(skyC * 1.05, fogC, horizon);
    float dusk = 1.0 - smoothstep(0.0, 0.35, abs(sunUp));
    col += vec3(1.0, 0.45, 0.18) * dusk * (1.0 - rain) * pow(sunDot, 6.0) * 0.65 * (0.3 + horizon);
#ifdef ATMOSPHERE
    col = mix(col, onaAtmosphere(dir, sunDir, upDir, rain), 0.75);
#endif
    if (up < 0.0) col = mix(col, fogC * 0.8, clamp(-up * 3.0, 0.0, 1.0));
    return col;
}

// cheap value noise for the clouds
float onaHash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
float onaValueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(onaHash(i), onaHash(i + vec2(1.0, 0.0)), f.x),
               mix(onaHash(i + vec2(0.0, 1.0)), onaHash(i + vec2(1.0, 1.0)), f.x), f.y);
}

// labPBR normal map (R, G = normal x, y; B = ambient occlusion)
vec3 onaDecodeNormal(vec4 n) {
    vec2 xy = n.xy * 2.0 - 1.0;
    return vec3(xy, sqrt(clamp(1.0 - dot(xy, xy), 0.0, 1.0)));
}

// GGX highlight with Schlick fresnel, energy kept in a sane range for an LDR pipeline
vec3 onaSpecular(vec3 n, vec3 V, vec3 L, float smoothness, vec3 f0) {
    float rough = max((1.0 - smoothness) * (1.0 - smoothness), 0.02);
    vec3 H = normalize(L + V);
    float NdotH = max(dot(n, H), 0.0);
    float NdotL = max(dot(n, L), 0.0);
    float NdotV = max(dot(n, V), 0.001);
    float VdotH = max(dot(V, H), 0.0);
    float a2 = rough * rough;
    float d = NdotH * NdotH * (a2 - 1.0) + 1.0;
    float D = a2 / (3.14159 * d * d);
    float k = rough * 0.5;
    float G = (NdotL / (NdotL * (1.0 - k) + k)) * (NdotV / (NdotV * (1.0 - k) + k));
    vec3 F = f0 + (1.0 - f0) * pow(1.0 - VdotH, 5.0);
    return min(D * G * F / (4.0 * NdotV * max(NdotL, 0.001) + 0.001) * NdotL, vec3(8.0));
}
