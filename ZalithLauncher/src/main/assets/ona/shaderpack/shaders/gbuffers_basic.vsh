#version 120
varying vec4 glcolor;
void main() {
    glcolor = gl_Color;
    gl_Position = ftransform();
}
