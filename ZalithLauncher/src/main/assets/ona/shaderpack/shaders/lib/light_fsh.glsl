// ONA Mobile Shaders - lighting for the pixels (forward shading)
uniform float nightVision;
uniform int isEyeInWater;

varying vec3 sunLight;
varying vec3 skyLight;
varying vec3 lightDir;

// albedo: texture x vertex colour; lm: block light, sky light (0-1); vis: shadow (0-1)
vec3 onaShade(vec3 albedo, vec3 normal, vec2 lm, float vis, float ao) {
    float NdotL = max(dot(normal, lightDir), 0.0);
    // soft wrap so faces turned a little away from the sun are not black
    float wrap = clamp((dot(normal, lightDir) + 0.25) / 1.25, 0.0, 1.0);
    float skyAmount = lm.y * lm.y;
    vec3 ambient = skyLight * skyAmount * (0.55 + 0.45 * wrap) + vec3(0.030, 0.032, 0.040);
    vec3 torch = vec3(1.00, 0.60, 0.30) * (pow(lm.x, 2.4) * 2.0 + pow(lm.x, 8.0) * 1.5);
    vec3 direct = sunLight * NdotL * vis * smoothstep(0.05, 0.45, lm.y);
    vec3 light = (ambient + direct) * ao + torch + nightVision * 0.45;
    return albedo * light;
}
