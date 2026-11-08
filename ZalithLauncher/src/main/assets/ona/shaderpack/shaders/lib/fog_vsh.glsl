// ONA Mobile Shaders - fog colour worked out per vertex: the sky lookup is the costly part of the
// fog, and it changes slowly across a face
uniform vec3 skyColor;
uniform vec3 fogColor;
uniform int isEyeInWater;
varying vec3 fogTint;

void onaFogTint(vec3 viewPos) {
    fogTint = isEyeInWater == 1 ? fogColor * 0.9
        : onaSky(normalize(viewPos), normalize(sunPosition), normalize(upPosition), skyColor, fogColor, rainStrength);
}
