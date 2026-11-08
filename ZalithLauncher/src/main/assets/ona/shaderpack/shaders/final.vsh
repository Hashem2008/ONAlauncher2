#version 120
uniform mat4 gbufferProjection;
uniform vec3 sunPosition;
uniform vec3 upPosition;
uniform float rainStrength;
varying vec2 texcoord;
varying vec2 sunScreen;
varying float sunAmount;
varying vec3 rayColor;
void main() {
    gl_Position = ftransform();
    texcoord = gl_MultiTexCoord0.xy;
    vec4 sp = gbufferProjection * vec4(sunPosition, 1.0);
    sunScreen = sp.w > 0.0 ? (sp.xy / sp.w) * 0.5 + 0.5 : vec2(-1.0);
    float sunUp = dot(normalize(sunPosition), normalize(upPosition));
    sunAmount = (sp.w > 0.0 ? 1.0 : 0.0) * smoothstep(-0.05, 0.15, sunUp) * (1.0 - rainStrength);
    rayColor = mix(vec3(1.0, 0.55, 0.30), vec3(1.0, 0.92, 0.80), smoothstep(0.0, 0.4, sunUp));
}
