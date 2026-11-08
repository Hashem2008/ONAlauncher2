#version 120
// sun, moon (and the End sky) to colortex2, added over the painted sky in deferred
/* RENDERTARGETS: 2 */
uniform sampler2D texture;
varying vec2 texcoord;
varying vec4 glcolor;
void main() {
    vec4 c = texture2D(texture, texcoord) * glcolor;
    gl_FragData[0] = vec4(c.rgb * c.a * 1.25, 1.0);
}
