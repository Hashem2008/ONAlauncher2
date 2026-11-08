#version 120
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/light_fsh.glsl"

uniform sampler2D texture;
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

void main() {
    vec4 albedo = texture2D(texture, texcoord) * glcolor;
    if (albedo.a < 0.02) discard;
    // particles and clouds face the camera: light them half by the sun, half by the sky
    vec3 light = skyLight * lmcoord.y * lmcoord.y + sunLight * 0.45 * smoothstep(0.3, 0.9, lmcoord.y)
               + vec3(1.0, 0.6, 0.3) * pow(lmcoord.x, 2.4) * 2.0 + 0.04 + nightVision * 0.45;
    vec3 color = onaTonemap(albedo.rgb * light);
    float d = length(viewPos) / far;
    float fog = clamp((d - FOG_START) / (1.0 - FOG_START), 0.0, 1.0);
    vec3 fogTint = onaSky(normalize(viewPos), normalize(sunPosition), normalize(upPosition), skyColor, fogColor, rainStrength);
    color = mix(color, fogTint, fog * fog);
    gl_FragData[0] = vec4(color, albedo.a);
}
