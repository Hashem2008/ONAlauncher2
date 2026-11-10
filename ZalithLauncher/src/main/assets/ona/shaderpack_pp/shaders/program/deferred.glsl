// ONA Shader ++ - after the opaque world: the sky for every pixel nothing covers, the sun,
// moon and stars added on top, and ambient occlusion. NO_SKY (Nether, End) keeps the game's
// own background and only adds what was drawn into colortex2.
#include "/lib/settings.glsl"
#include "/lib/common.glsl"

/* RENDERTARGETS: 0 */
/*
const int colortex2Format = RGBA8;
const bool colortex2Clear = true;
const vec4 colortex2ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
*/

uniform sampler2D colortex0;
uniform sampler2D colortex2;
uniform sampler2D depthtex0;
uniform mat4 gbufferProjection;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform vec3 sunPosition;
uniform vec3 upPosition;
uniform vec3 skyColor;
uniform vec3 fogColor;
uniform vec3 cameraPosition;
uniform float rainStrength;
uniform float frameTimeCounter;

varying vec2 texcoord;

#ifdef VOLUMETRIC_CLOUDS
#ifdef ONA_CLOUDS
// ONA Shader ++ clouds: a layer of air from 240 to 400 blocks up that the view ray is walked
// through. At every step: how thick the cloud is there (the same slow noise the cloud shadows on
// the ground use, so they match; round bottoms, tops that thin out, small puffs eating the
// edges), and how much cloud lies between that spot and the sun (its own shadow: dark bellies,
// bright sunlit sides, glowing rims when the sun is behind). Each pixel starts at its own depth
// (and another one every frame with temporal smoothing), so few steps look like many.
const float ONA_CLOUD_BASE = 240.0;
const float ONA_CLOUD_TOP = 400.0;

float onaCloudDensity(vec3 p) {
    vec2 q = p.xz * 0.0025 + vec2(frameTimeCounter * 0.004, frameTimeCounter * 0.0015);
    float h = clamp((p.y - ONA_CLOUD_BASE) / (ONA_CLOUD_TOP - ONA_CLOUD_BASE), 0.0, 1.0);
    float n = onaValueNoise(q) * 0.65 + onaValueNoise(q * 2.7) * 0.35;
    float shape = smoothstep(0.0, 0.18, h) * (1.0 - smoothstep(0.45, 1.0, h));
    float d = clamp((n - (0.55 - rainStrength * 0.25 + h * 0.22)) * 4.0, 0.0, 1.0) * shape;
    if (d <= 0.0) return 0.0;
    float detail = onaValueNoise(q * 9.0 + vec2(p.y * 0.013, -p.y * 0.009));
    return clamp(d - detail * 0.35 * (1.0 - d), 0.0, 1.0);
}

// rgb: cloud light (screen colours), a: how much of the sky behind still shows
vec4 onaVolumeClouds(vec3 dirW, vec3 lightW, vec3 lit, vec3 ambient) {
    if (dirW.y < 0.02 || cameraPosition.y > ONA_CLOUD_TOP) return vec4(0.0, 0.0, 0.0, 1.0);
    float t0 = max((ONA_CLOUD_BASE - cameraPosition.y) / dirW.y, 0.0);
    float t1 = min((ONA_CLOUD_TOP - cameraPosition.y) / dirW.y, t0 + 2200.0);
    float dt = (t1 - t0) / float(CLOUD_STEPS);
    float dither = onaFrameNoise(gl_FragCoord.xy + 11.0);
    float mu = dot(dirW, lightW);
    float g = 0.6;
    float phase = (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * mu, 1.5) * 0.35 + 0.55;
    float through = 1.0;
    vec3 light = vec3(0.0);
    for (int i = 0; i < CLOUD_STEPS; i++) {
        vec3 p = cameraPosition + dirW * (t0 + (float(i) + dither) * dt);
        float den = onaCloudDensity(p);
        if (den > 0.001) {
            float above = 0.0;
            for (int j = 1; j <= CLOUD_LIGHT_STEPS; j++) above += onaCloudDensity(p + lightW * (30.0 * float(j)));
            float shadow = exp(-above * 1.1);
            float powder = 1.0 - exp(-den * 4.0 - above * 2.0) * 0.6;
            float a = 1.0 - exp(-den * dt * 0.045);
            float h = clamp((p.y - ONA_CLOUD_BASE) / (ONA_CLOUD_TOP - ONA_CLOUD_BASE), 0.0, 1.0);
            light += (lit * (shadow * phase * powder) + ambient * (0.55 + 0.45 * h)) * (through * a);
            through *= 1.0 - a;
            if (through < 0.03) break;
        }
    }
    // the air near the horizon hides them
    float fade = smoothstep(0.02, 0.14, dirW.y);
    return vec4(light * fade, mix(1.0, through, fade));
}
#endif
#endif

#ifdef NIGHT_SKY
#ifndef NO_SKY
uniform int moonPhase;

// a hash without sin(): the same stars on every phone GPU (sin of big numbers differs between them)
float onaHash3(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

// ONA Shader ++ night: stars, the Milky Way and northern lights for a view direction (world axes).
// Added on top of the night sky, behind the clouds; "night" is how dark it is (0 by day).
vec3 onaNightSky(vec3 d, float night, float time) {
    if (d.y < -0.05 || night <= 0.0) return vec3(0.0);
    vec3 glow = vec3(0.0);
    // the Milky Way: a soft tilted band across the sky, thicker and thinner along its way, with a
    // warmer core line
    float across = dot(d, normalize(vec3(0.55, 0.35, 0.76)));
    float dust = onaValueNoise(d.xz * 5.0 + d.y * 3.1) * 0.6 + onaValueNoise(d.zy * 11.0 + d.x * 7.3) * 0.4;
    float band = exp(-across * across * 14.0) * (0.35 + 0.65 * dust);
    glow += vec3(0.50, 0.56, 0.78) * band * 0.30;
    glow += vec3(0.80, 0.55, 0.45) * exp(-across * across * 60.0) * dust * dust * 0.14;
    // stars: the sky ball cut into little cells, a star in some of them (two sizes of cells: a few
    // bright stars and many faint ones, more of them inside the band), each twinkling at its own pace
    for (int layer = 0; layer < 2; layer++) {
        vec3 p = d * (layer == 0 ? 90.0 : 190.0);
        vec3 cell = floor(p);
        float pick = onaHash3(cell);
        if (pick < (layer == 0 ? 0.05 : 0.08) + band * 0.14) {
            vec3 h = vec3(onaHash3(cell + 17.3), onaHash3(cell + 41.7), onaHash3(cell + 73.1));
            float size = layer == 0 ? 0.24 + 0.20 * h.x : 0.20;
            float star = smoothstep(size, 0.0, length(p - (cell + 0.25 + h * 0.5)));
            float twinkle = 0.70 + 0.30 * sin(time * (1.5 + h.z * 4.0) + h.y * 40.0);
            vec3 tint = mix(vec3(0.75, 0.85, 1.00), vec3(1.00, 0.88, 0.70), h.z);
            glow += tint * star * star * twinkle * (layer == 0 ? 1.0 : 0.6);
        }
    }
    // northern lights on the dark nights around the new moon: sheets hanging in the north that
    // sway slowly, green below and violet above, made of thin rays. A sheet is where a slow noise
    // crosses one level; looked up at 8 heights (each pixel at its own in-between heights) the
    // line becomes a curtain
    if (moonPhase >= 3 && moonPhase <= 5 && d.y > 0.04) {
        float dither = onaFrameNoise(gl_FragCoord.xy + 5.0);
        vec3 aurora = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float f = (float(i) + dither) / 8.0;
            vec2 a = d.xz * ((1.0 + f * 0.55) / (d.y + 0.12));
            float n = onaValueNoise(a * 0.55 + vec2(time * 0.010, 0.0)) + onaValueNoise(a * 1.7 - vec2(0.0, time * 0.016)) * 0.35;
            float sheet = smoothstep(0.09, 0.0, abs(n - 0.62));
            float rays = 0.6 + 0.4 * onaValueNoise(vec2(a.x * 9.0 + a.y * 7.0, time * 0.3));
            aurora += mix(vec3(0.10, 1.00, 0.42), vec3(0.55, 0.22, 0.95), f) * sheet * rays * (1.0 - f * 0.6);
        }
        float north = smoothstep(-0.15, 0.55, -d.z);
        float height = smoothstep(0.04, 0.20, d.y) * (1.0 - smoothstep(0.55, 0.95, d.y));
        glow += aurora * north * height * 0.10;
    }
    // the air near the horizon hides them
    return glow * night * smoothstep(-0.05, 0.25, d.y);
}
#endif
#endif

vec3 onaViewPos(vec2 uv, float depth) {
    vec4 p = gbufferProjectionInverse * vec4(vec3(uv, depth) * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

void main() {
    vec3 color = texture2D(colortex0, texcoord).rgb;
    float depth = texture2D(depthtex0, texcoord).r;
    if (depth >= 1.0) {
        vec3 objects = texture2D(colortex2, texcoord).rgb;
#ifndef NO_SKY
        vec3 dir = normalize(onaViewPos(texcoord, 1.0));
        vec3 sunDir = normalize(sunPosition);
        vec3 up = normalize(upPosition);
        vec3 sky = onaSky(dir, sunDir, up, skyColor, fogColor, rainStrength);
#ifdef NIGHT_SKY
        // before the clouds, so they cover it
        sky += onaNightSky(mat3(gbufferModelViewInverse) * dir, (1.0 - smoothstep(-0.22, 0.02, dot(sunDir, up))) * (1.0 - rainStrength), frameTimeCounter);
#endif
#ifdef ONA_CLOUDS
        vec3 dirW = mat3(gbufferModelViewInverse) * dir;
#ifdef VOLUMETRIC_CLOUDS
        {
            float sunUpV = dot(sunDir, up);
            vec3 litV = mix(vec3(0.35, 0.38, 0.45), vec3(1.0, 0.97, 0.92), smoothstep(-0.1, 0.25, sunUpV));
            litV = mix(litV, vec3(1.0, 0.62, 0.40), (1.0 - smoothstep(0.0, 0.35, abs(sunUpV))) * 0.6);
            litV *= 1.0 - rainStrength * 0.45;
            vec3 sunW = mat3(gbufferModelViewInverse) * sunDir;
            // the moon lights them at night, from the other side
            // what the open sky throws onto the clouds from all sides
            vec3 ambientV = mix(skyColor, vec3(0.80, 0.85, 0.95), 0.45) * 0.75 * (0.4 + 0.6 * smoothstep(-0.1, 0.25, sunUpV));
            vec4 clouds = onaVolumeClouds(dirW, sunUpV > 0.0 ? sunW : -sunW, litV, ambientV);
            sky = sky * clouds.a + clouds.rgb;
            objects *= clouds.a;
        }
#else
        // a cloud layer 300 blocks up, two octaves of noise drifting with time
        if (dirW.y > 0.02) {
            vec2 p = (cameraPosition.xz + dirW.xz * ((300.0 - cameraPosition.y) / dirW.y)) * 0.0025;
            p += vec2(frameTimeCounter * 0.004, frameTimeCounter * 0.0015);
            float n = onaValueNoise(p) * 0.65 + onaValueNoise(p * 2.7) * 0.35;
            float cover = smoothstep(0.55 - rainStrength * 0.25, 0.85, n) * smoothstep(0.02, 0.18, dirW.y);
            float sunUp = dot(sunDir, up);
            vec3 lit = mix(vec3(0.35, 0.38, 0.45), vec3(1.0, 0.97, 0.92), smoothstep(-0.1, 0.25, sunUp));
            lit = mix(lit, vec3(1.0, 0.62, 0.40), (1.0 - smoothstep(0.0, 0.35, abs(sunUp))) * 0.6);
            lit *= 1.0 - rainStrength * 0.45;
            sky = mix(sky, lit * (0.85 + 0.15 * n), cover * 0.9);
            objects *= 1.0 - cover;
        }
#endif
#endif
        color = sky + objects;
#else
        color += objects;
#endif
    }
#ifdef SSAO
    else if (depth > 0.56) {
        vec3 p = onaViewPos(texcoord, depth);
        // far away the corners are a pixel wide: no samples there (the costly part of the pass)
        if (p.z > -40.0 && (SHADER_DISTANCE == 0 || -p.z < float(SHADER_DISTANCE) * 16.0)) {
        vec3 n = normalize(cross(dFdx(p), dFdy(p)));
        vec3 helper = abs(n.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
        vec3 t = normalize(cross(helper, n));
        vec3 b = cross(n, t);
        float angle = onaFrameNoise(gl_FragCoord.xy) * 6.2831853;
        float radius = 0.6;
        float occlusion = 0.0;
        for (int i = 0; i < SSAO_SAMPLES; i++) {
            float f = (float(i) + 0.5) / float(SSAO_SAMPLES);
            float a = angle + float(i) * 2.3999632;
            float r = sqrt(f);
            vec3 k = vec3(cos(a) * r, sin(a) * r, sqrt(max(1.0 - f, 0.0)) + 0.1) * (0.25 + 0.75 * f);
            vec3 s = p + (t * k.x + b * k.y + n * k.z) * radius;
            vec4 sp = gbufferProjection * vec4(s, 1.0);
            vec2 suv = sp.xy / sp.w * 0.5 + 0.5;
            if (suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0) continue;
            float sceneZ = onaViewPos(suv, texture2D(depthtex0, suv).r).z;
            float range = smoothstep(0.0, 1.0, radius / max(abs(p.z - sceneZ), 0.001));
            occlusion += (sceneZ >= s.z + 0.03 ? 1.0 : 0.0) * range;
        }
        // fades out before the cut at 40 blocks, so there is no line
        float fade = 1.0 - smoothstep(28.0, 40.0, -p.z);
        color *= mix(1.0, 1.0 - occlusion / float(SSAO_SAMPLES) * fade, 0.75);
        }
    }
#endif
    gl_FragData[0] = vec4(color, 1.0);
}
