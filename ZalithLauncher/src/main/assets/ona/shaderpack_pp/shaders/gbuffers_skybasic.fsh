#version 120
// The sky itself is painted for the whole screen in deferred (no edge where the game's sky disc
// ends). Here only the stars go to colortex2, added on top there.
/* RENDERTARGETS: 2 */
varying vec4 glcolor;
varying vec3 viewPos;
varying float star;
void main() {
    if (star < 0.5) discard;
    gl_FragData[0] = vec4(glcolor.rgb * 1.2 * glcolor.a, 1.0);
}
