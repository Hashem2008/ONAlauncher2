// ONA Mobile Shaders - light colours, worked out per vertex (cheap) and passed to the pixels
uniform vec3 sunPosition;
uniform vec3 upPosition;
uniform vec3 shadowLightPosition;
uniform float rainStrength;

varying vec3 sunLight;
varying vec3 skyLight;
varying vec3 lightDir;

void onaLightColors() {
    vec3 sunDir = normalize(sunPosition);
    vec3 up = normalize(upPosition);
    float sunUp = dot(sunDir, up);
    float day = smoothstep(-0.12, 0.22, sunUp);
    float dusk = 1.0 - smoothstep(0.0, 0.40, abs(sunUp));
    vec3 noon = vec3(1.00, 0.94, 0.84) * 2.10;
    vec3 sunset = vec3(1.00, 0.56, 0.30) * 1.50;
    vec3 moon = vec3(0.42, 0.52, 0.80) * 0.28;
    sunLight = mix(moon, mix(noon, sunset, dusk), day) * SUN_STRENGTH;
    sunLight *= 1.0 - rainStrength * 0.85;
    vec3 daySky = vec3(0.50, 0.66, 0.95) * 0.85;
    vec3 nightSky = vec3(0.10, 0.13, 0.22) * 0.55;
    skyLight = mix(nightSky, daySky, day);
    skyLight = mix(skyLight, vec3(0.42, 0.44, 0.48) * (0.15 + 0.55 * day), rainStrength * 0.7);
    lightDir = normalize(shadowLightPosition);
}
