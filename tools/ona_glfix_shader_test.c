#define Java_org_lwjgl_opengl_PojavRendererInit_nativeWrapGlFunction unused_jni
#include "/home/user/ONAlauncher2/ZalithLauncher/src/main/jni/ona_glfix.c"
static char* captured;
static void fake_ShaderSource(GLuint s, GLsizei c, const GLchar* const* str, const GLint* len) { captured = strdup(str[0]); }
static void fake_GetShaderiv(GLuint s, GLenum p, GLint* v) { *v = GL_FRAGMENT_SHADER; }
int main(int argc, char** argv) {
    FILE* f = fopen(argv[1], "rb"); fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    char* src = malloc(n + 1); fread(src, 1, n, f); src[n] = 0; fclose(f);
    real_ShaderSource = fake_ShaderSource; real_GetShaderiv = fake_GetShaderiv;
    // split in two strings to test joining
    const char* parts[2] = { src, src + n / 2 };
    GLint lens[2] = { (GLint)(n / 2), -1 };
    ona_ShaderSource(1, 2, parts, lens);
    fputs(captured, stdout);
    return 0;
}
