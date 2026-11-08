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

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 viewPos;
varying vec3 normal;
varying vec3 tangent;
varying vec3 binormal;
varying float tangentOk;
varying float blockId;

void main() {
    vec4 albedo = texture2D(texture, texcoord) * glcolor;
    if (albedo.a < 0.1) discard;
    vec3 geoN = normalize(normal);
    vec3 n = geoN;
    float ao = 1.0;
    vec3 up = normalize(upPosition);
#if PBR > 0
    if (tangentOk > 0.5) {
        vec4 nmap = texture2D(normals, texcoord);
        // only a real normal map: an empty texture (resource pack without maps) would bend
        // every face to black
        if (nmap.r + nmap.g > 0.02) {
            vec3 bent = mat3(tangent, binormal, geoN) * onaDecodeNormal(nmap);
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
    vec4 spec = texture2D(specular, texcoord);
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
    float geoNdotL = dot(geoN, lightDir);
#ifdef SHADOWS
    float vis = sss > 0.0 ? onaShadowAny() : onaShadow(geoNdotL);
#else
    float vis = smoothstep(0.75, 0.97, lmcoord.y);
#endif
    vec3 color = onaShade(albedo.rgb, n, lmcoord, vis * (sss > 0.0 && geoNdotL < 0.0 ? 0.0 : 1.0), ao);
    vec3 V = normalize(-viewPos);
    float outside = smoothstep(0.05, 0.45, lmcoord.y);
    if (sss > 0.0) {
        // light coming through thin leaves when looking towards the sun
        float through = pow(max(dot(-V, lightDir), 0.0), 3.0) * 0.9 + max(-geoNdotL, 0.0) * 0.3;
        color += albedo.rgb * sunLight * through * vis * sss * outside;
    }
    if (smoothness > 0.02 || metal) {
        vec3 f0 = metal ? albedo.rgb : vec3(reflectance);
        color += sunLight * onaSpecular(n, V, lightDir, smoothness, f0) * vis * outside;
        if (smoothness > 0.4) {
            vec3 r = reflect(-V, n);
            vec3 sky = onaSky(r, normalize(sunPosition), up, skyColor, fogColor, rainStrength);
            float fresnel = reflectance + (1.0 - reflectance) * pow(1.0 - max(dot(n, V), 0.0), 5.0);
            float amount = (smoothness - 0.4) / 0.6;
            color = mix(color, sky * (metal ? albedo.rgb * 1.4 : vec3(1.0)), clamp(fresnel * amount * lmcoord.y, 0.0, 0.8));
        }
        if (metal) color *= mix(vec3(1.0), albedo.rgb * 1.2 + 0.2, 0.5);
    }
    color += albedo.rgb * emission * 3.0;
    color = onaTonemap(color * exposure);
    float d = length(viewPos) / far;
    float fog = clamp((d - FOG_START) / (1.0 - FOG_START), 0.0, 1.0);
    if (isEyeInWater == 1) fog = clamp(length(viewPos) / 48.0, 0.0, 1.0);
    vec3 fogTint = isEyeInWater == 1 ? fogColor * 0.9 : onaSky(normalize(viewPos), normalize(sunPosition), up, skyColor, fogColor, rainStrength);
    color = mix(color, fogTint, fog * fog);
    gl_FragData[0] = vec4(color, albedo.a);
}
