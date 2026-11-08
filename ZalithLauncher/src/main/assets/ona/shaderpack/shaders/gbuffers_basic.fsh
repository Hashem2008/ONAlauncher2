#version 120
varying vec4 glcolor;
void main() {
    gl_FragData[0] = glcolor;
}
