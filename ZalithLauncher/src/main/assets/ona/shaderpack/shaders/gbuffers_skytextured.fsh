#version 120
uniform sampler2D texture;
varying vec2 texcoord;
varying vec4 glcolor;
void main() {
    vec4 c = texture2D(texture, texcoord) * glcolor;
    gl_FragData[0] = vec4(c.rgb * 1.25, c.a);
}
