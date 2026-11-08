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
uniform float far;

varying vec2 texcoord;
varying vec2 lmcoord;
varying vec4 glcolor;
varying vec3 viewPos;
varying vec3 normal;
varying vec3 tangent;
varying vec3 binormal;

void main() {
    vec4 albedo = texture2D(texture, texcoord) * glcolor;
    if (albedo.a < 0.1) discard;
    vec3 n = normalize(normal);
    float ao = 1.0;
#if PBR > 0
    vec4 nmap = texture2D(normals, texcoord);
    n = normalize(mat3(normalize(tangent), normalize(binormal), n) * onaDecodeNormal(nmap));
    ao = mix(1.0, nmap.b, 0.8);
#endif
    float NdotL = dot(n, lightDir);
#ifdef SHADOWS
    float vis = onaShadow(dot(normalize(normal), lightDir));
#else
    float vis = smoothstep(0.75, 0.97, lmcoord.y);
#endif
    vec3 color = onaShade(albedo.rgb, n, lmcoord, vis, ao);
#if PBR > 1
    vec4 spec = texture2D(specular, texcoord);
    float smoothness = spec.r;
    bool metal = spec.g > 0.89;
    vec3 f0 = metal ? albedo.rgb : vec3(max(spec.g, 0.04));
    vec3 V = normalize(-viewPos);
    vec3 H = normalize(lightDir + V);
    float rough = max(1.0 - smoothness, 0.04);
    float power = 2.0 / (rough * rough * rough * rough) - 2.0;
    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - max(dot(H, V), 0.0), 5.0);
    float highlight = pow(max(dot(n, H), 0.0), min(power, 2048.0)) * (power + 8.0) / 25.0;
    color += sunLight * fresnel * highlight * max(NdotL, 0.0) * vis * smoothness;
    if (smoothness > 0.45) {
        vec3 r = reflect(-V, n);
        vec3 sky = onaSky(r, normalize(sunPosition), normalize(upPosition), skyColor, fogColor, rainStrength);
        float amount = (smoothness - 0.45) / 0.55;
        color = mix(color, sky * (metal ? albedo.rgb * 1.5 : vec3(1.0)), fresnel * amount * lmcoord.y * 0.6);
    }
    if (metal) color *= mix(vec3(1.0), albedo.rgb * 1.2 + 0.2, 0.5);
    // labPBR emission: alpha 0..254, 255 = none
    float emission = spec.a < 0.999 ? spec.a : 0.0;
    color += albedo.rgb * emission * 3.0;
#endif
    color = onaTonemap(color);
    float d = length(viewPos) / far;
    float fog = clamp((d - FOG_START) / (1.0 - FOG_START), 0.0, 1.0);
    if (isEyeInWater == 1) fog = clamp(length(viewPos) / 48.0, 0.0, 1.0);
    vec3 fogTint = isEyeInWater == 1 ? fogColor * 0.9 : onaSky(normalize(viewPos), normalize(sunPosition), normalize(upPosition), skyColor, fogColor, rainStrength);
    color = mix(color, fogTint, fog * fog);
    gl_FragData[0] = vec4(color, albedo.a);
}
