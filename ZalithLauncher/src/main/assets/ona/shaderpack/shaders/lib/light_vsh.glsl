// ONA Mobile Shaders - light colours, worked out per vertex (cheap) and passed to the pixels
uniform vec3 sunPosition;
uniform vec3 upPosition;
uniform vec3 shadowLightPosition;
uniform float rainStrength;
uniform ivec2 eyeBrightnessSmooth;

varying vec3 sunLight;
varying vec3 skyLight;
varying vec3 lightDir;
varying vec3 upDir;
varying float exposure;

void onaLightColors() {
    vec3 sunDir = normalize(sunPosition);
    vec3 up = normalize(upPosition);
    float sunUp = dot(sunDir, up);
    float day = smoothstep(-0.12, 0.22, sunUp);
    float dusk = 1.0 - smoothstep(0.0, 0.40, abs(sunUp));
    // sun and sky balanced so a sunlit block shows its own colour (white light all together)
    vec3 noon = vec3(1.00, 0.92, 0.78) * 1.585;
    vec3 sunset = vec3(1.00, 0.56, 0.30) * 1.19;
    vec3 moon = vec3(0.42, 0.52, 0.80) * 0.28;
    sunLight = mix(moon, mix(noon, sunset, dusk), day) * SUN_STRENGTH;
    sunLight *= 1.0 - rainStrength * 0.85;
    // sun about 2.3x the sky light: shade is about a third of sunlight, a little blue, like outside
    vec3 daySky = vec3(0.70, 0.82, 1.00) * 0.78;
    vec3 nightSky = vec3(0.10, 0.13, 0.22) * 0.60;
    skyLight = mix(nightSky, daySky, day);
    skyLight = mix(skyLight, vec3(0.42, 0.44, 0.48) * (0.15 + 0.55 * day), rainStrength * 0.7);
    lightDir = normalize(shadowLightPosition);
    upDir = up;
#ifdef AUTO_EXPOSURE
    // the eyes adapt: in a cave the image gets brighter, in the open it stays as it is
    exposure = mix(1.9, 1.0, smoothstep(0.0, 0.55, float(eyeBrightnessSmooth.y) / 240.0));
#else
    exposure = 1.0;
#endif
}
