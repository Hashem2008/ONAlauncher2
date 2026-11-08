#version 120
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/light_fsh.glsl"
#include "/lib/shadow_fsh.glsl"

uniform sampler2D texture;
#if PBR > 0
uniform sampler2D normals;
#endif
#if PBR > 1
uniform sampler2D specular;
#endif
uniform vec3 sunPosition;
uniform vec3 upPosition;
uniform vec3 skyColor;
uniform vec3 fogColor;
uniform float rainStrength;
uniform float wetness;
uniform float far;
#if defined RAIN_PUDDLES && defined RAIN_RIPPLES
uniform mat4 gbufferModelView;
#endif
#if (defined RAIN_PUDDLES || defined WATER_CAUSTICS || defined CLOUD_SHADOWS || defined SPARKLE)
uniform mat4 gbufferModelViewInverse;
uniform vec3 cameraPosition;
uniform float frameTimeCounter;
#endif

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 viewPos;
varying vec3 normal;
varying vec3 tangent;
varying vec3 binormal;
varying float tangentOk;
varying float blockId;
varying vec3 fogTint;
#if POM_SAMPLES > 0
varying vec4 tileBounds;
varying vec2 tileCoord;
#endif

void main() {
    vec2 uv = texcoord;
    vec3 geoN = normalize(normal);
#if POM_SAMPLES > 0 && PBR > 0
    // parallax: walk into the surface along the view ray until the height map is reached
    // (only HD packs with height in the normal map alpha move; flat maps stay as they are)
    if (tangentOk > 0.5 && length(viewPos) < 24.0) {
        vec3 rayTS = vec3(dot(viewPos, tangent), dot(viewPos, binormal), dot(viewPos, geoN));
        if (rayTS.z < -0.001) {
            float layer = 1.0 / float(POM_SAMPLES);
            vec2 delta = rayTS.xy / -rayTS.z * POM_DEPTH * layer * vec2(1.0, -1.0);
            vec2 coord = tileCoord;
            float level = 1.0;
            float height = texture2D(normals, fract(coord) * tileBounds.zw + tileBounds.xy).a;
            for (int i = 0; i < POM_SAMPLES; i++) {
                if (height >= level) break;
                level -= layer;
                coord += delta;
                height = texture2D(normals, fract(coord) * tileBounds.zw + tileBounds.xy).a;
            }
            uv = fract(coord) * tileBounds.zw + tileBounds.xy;
        }
    }
#endif
    vec4 albedo = texture2D(texture, uv) * glcolor;
    if (albedo.a < 0.1) discard;
    vec3 n = geoN;
    float ao = 1.0;
    vec3 up = normalize(upPosition);
#if PBR > 0
    if (tangentOk > 0.5) {
        vec4 nmap = texture2D(normals, uv);
        // only a real normal map: an empty texture (resource pack without maps) would bend
        // every face to black
        if (nmap.r + nmap.g > 0.02) {
            vec3 tn = onaDecodeNormal(nmap);
            tn.xy *= NORMAL_STRENGTH;
            vec3 bent = mat3(tangent, binormal, geoN) * tn;
            float len = length(bent);
            if (len > 0.1) {
                n = bent / len;
                ao = mix(1.0, clamp(nmap.b, 0.0, 1.0), 0.7);
            }
        }
    }
#endif
    float smoothness = 0.0;
    float reflectance = 0.04;
    bool metal = false;
    float emission = 0.0;
    float sss = 0.0;
#if PBR > 1
    vec4 spec = texture2D(specular, uv);
    smoothness = spec.r;
    metal = spec.g > 0.898;
    reflectance = max(spec.g, 0.04);
    emission = spec.a < 0.999 ? spec.a : 0.0;
    sss = spec.b > 0.254 ? (spec.b - 0.254) / 0.746 : 0.0;
#endif
    bool foliage = blockId > 10000.5 && blockId < 10003.5;
#ifdef FOLIAGE_SSS
    if (foliage) sss = max(sss, 0.6);
#endif
#ifdef WET_SURFACES
    // rain: open ground gets darker and shiny, walls a little
    if (!foliage) {
        float wet = wetness * smoothstep(0.88, 0.97, lmcoord.y) * (dot(geoN, up) > 0.7 ? 1.0 : 0.35);
        smoothness = mix(smoothness, 0.92, wet * 0.85);
        reflectance = max(reflectance, 0.02 + wet * 0.02);
        albedo.rgb *= 1.0 - wet * 0.28;
    }
#endif
#if (defined RAIN_PUDDLES || defined WATER_CAUSTICS || defined CLOUD_SHADOWS || defined SPARKLE)
    vec3 worldPos = (gbufferModelViewInverse * vec4(viewPos, 1.0)).xyz + cameraPosition;
#endif
#ifdef RAIN_PUDDLES
    // puddles: the low spots of open flat ground fill with water while it rains, and dry up after
    if (!foliage && wetness > 0.01 && dot(geoN, up) > 0.9) {
        float mask = onaValueNoise(worldPos.xz * 0.23) * 0.65 + onaValueNoise(worldPos.xz * 0.9) * 0.35;
        float level = 0.66 - wetness * 0.14;
        float puddle = smoothstep(level, level + 0.06, mask) * smoothstep(0.88, 0.97, lmcoord.y) * wetness;
        smoothness = mix(smoothness, 0.97, puddle);
        reflectance = max(reflectance, 0.02 * puddle);
        n = normalize(mix(n, geoN, puddle));
#ifdef RAIN_RIPPLES
        vec2 drops = onaRipples(worldPos.xz, frameTimeCounter) * rainStrength * puddle;
        n = normalize(n + mat3(gbufferModelView) * vec3(drops.x, 0.0, drops.y));
#endif
        albedo.rgb *= 1.0 - puddle * 0.35;
    }
#endif
    vec2 lm = onaLightmap(lmcoord, length(viewPos));
    float geoNdotL = dot(geoN, lightDir);
#ifdef SHADOWS
    float vis = sss > 0.0 ? onaShadowAny() : onaShadow(geoNdotL);
#else
    float vis = smoothstep(0.75, 0.97, lmcoord.y);
#endif
#ifdef CLOUD_SHADOWS
#ifdef ONA_CLOUDS
    {
        // where the sun ray from here crosses the cloud layer, 300 blocks up
        vec3 sunW = mat3(gbufferModelViewInverse) * lightDir;
        if (sunW.y > 0.05) vis *= 1.0 - onaCloudCover(worldPos.xz + sunW.xz * ((300.0 - worldPos.y) / sunW.y), frameTimeCounter, rainStrength) * 0.8;
    }
#endif
#endif
    vec3 color = onaShade(albedo.rgb, n, lm, vis * (sss > 0.0 && geoNdotL < 0.0 ? 0.0 : 1.0), ao);
#ifdef SPARKLE
    if ((blockId > 10029.5 && blockId < 10031.5) && dot(geoN, up) > 0.7) {
        // a few texels catch the sun, and change as you move (sand and snow glitter)
        vec3 Vs = normalize(-viewPos);
        float spark = onaHash(floor(worldPos.xz * 16.0) + floor((Vs.x + Vs.y) * 9.0));
        color += sunLight * smoothstep(0.993, 1.0, spark) * 4.0 * vis * max(dot(geoN, lightDir), 0.0);
    }
#endif
    vec3 V = normalize(-viewPos);
    float outside = smoothstep(0.05, 0.45, lmcoord.y);
    if (sss > 0.0) {
        // light coming through thin leaves when looking towards the sun
        float through = pow(max(dot(-V, lightDir), 0.0), 3.0) * 0.9 + max(-geoNdotL, 0.0) * 0.3;
        color += albedo.rgb * sunLight * through * vis * sss * outside;
    }
    if ((smoothness > 0.02 || metal) && SPECULAR_STRENGTH > 0.0) {
        vec3 f0 = metal ? albedo.rgb : vec3(reflectance);
        color += sunLight * onaSpecular(n, V, lightDir, smoothness, f0) * vis * outside * SPECULAR_STRENGTH;
        if (smoothness > 0.4) {
            vec3 r = reflect(-V, n);
            vec3 sky = onaUntonemap(onaSky(r, normalize(sunPosition), up, skyColor, fogColor, rainStrength)) / exposure;
            float fresnel = reflectance + (1.0 - reflectance) * pow(1.0 - max(dot(n, V), 0.0), 5.0);
            float amount = (smoothness - 0.4) / 0.6;
            color = mix(color, sky * (metal ? albedo.rgb * 1.4 : vec3(1.0)), clamp(fresnel * amount * lmcoord.y * SPECULAR_STRENGTH, 0.0, 0.8));
        }
        if (metal) color *= mix(vec3(1.0), albedo.rgb * 1.2 + 0.2, 0.5);
    }
    color += albedo.rgb * emission * 3.0;
#ifdef WATER_CAUSTICS
    if (isEyeInWater == 1) {
        // sunlight bent by the waves above: two drifting noise layers, bright where they meet
        float tc = frameTimeCounter * 0.7;
        float c1 = onaValueNoise(worldPos.xz * 0.9 + vec2(tc * 0.30, tc * 0.20));
        float c2 = onaValueNoise(worldPos.xz * 1.3 - vec2(tc * 0.25, -tc * 0.15));
        float caustic = pow(1.0 - abs(c1 - c2), 8.0);
        color += albedo.rgb * sunLight * caustic * 0.9 * vis * smoothstep(0.3, 0.8, lmcoord.y);
    }
#endif
    color = onaTonemap(color * exposure);
    float d = length(viewPos) / far;
    float fog = clamp((d - FOG_START) / (1.0 - FOG_START), 0.0, 1.0);
    if (isEyeInWater == 1) fog = clamp(length(viewPos) / 48.0, 0.0, 1.0);
    color = mix(color, fogTint, fog * fog);
#ifdef DISTANCE_HAZE
    // the air between the eye and far land takes over its colour (stronger in rain)
    color = mix(color, fogTint, (1.0 - exp(-length(viewPos) * (0.0035 + rainStrength * 0.008))) * 0.6);
#endif
    gl_FragData[0] = vec4(color, albedo.a);
}
