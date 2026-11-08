#version 120
varying vec4 glcolor;
varying vec3 viewPos;
varying float star;
void main() {
    glcolor = gl_Color;
    vec4 view = gl_ModelViewMatrix * gl_Vertex;
    viewPos = view.xyz;
    gl_Position = gl_ProjectionMatrix * view;
    star = (gl_Color.r == gl_Color.g && gl_Color.g == gl_Color.b && gl_Color.r > 0.0) ? 1.0 : 0.0;
}
