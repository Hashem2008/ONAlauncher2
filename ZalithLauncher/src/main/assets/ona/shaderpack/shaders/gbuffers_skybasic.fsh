#version 120
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
uniform vec3 sunPosition;
uniform vec3 upPosition;
uniform vec3 skyColor;
uniform vec3 fogColor;
uniform float rainStrength;
varying vec4 glcolor;
varying vec3 viewPos;
varying float star;
void main() {
    if (star > 0.5) {
        gl_FragData[0] = vec4(glcolor.rgb * 1.2, glcolor.a);
    } else {
        vec3 sky = onaSky(normalize(viewPos), normalize(sunPosition), normalize(upPosition), skyColor, fogColor, rainStrength);
        gl_FragData[0] = vec4(sky, 1.0);
    }
}
