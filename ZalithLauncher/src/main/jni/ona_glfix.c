//
// ONA: render target format fix for shader packs on OpenGL ES based renderers.
//
// Shader packs (Iris / Oculus / OptiFine) ask for desktop render target formats such as RGB16F,
// RGB16, RGBA16 or RGB8_SNORM. OpenGL ES cannot draw into most of them, so the framebuffer comes
// back incomplete (GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT, 36054) and the pack fails to load.
// LWJGL asks this file for its texture / renderbuffer allocation functions (see
// PojavRendererInit.wrapProvider) and gets thin wrappers that swap such formats for the nearest
// one ES can render to (RGBA16F, RGBA32F, RGBA8I, ...) before calling the renderer. Only empty
// allocations (no pixel data) are changed, uploads go through untouched. Incomplete framebuffers
// are written to the game log with their attachments, so the next problem is easy to find.
//

#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <ctype.h>
#include <stdarg.h>
#include <android/log.h>

#define TAG "ONA-GLFix"

typedef unsigned int GLenum;
typedef int GLint;
typedef int GLsizei;
typedef unsigned int GLuint;

#define GL_FLOAT 0x1406
#define GL_UNSIGNED_BYTE 0x1401
#define GL_RED 0x1903
#define GL_RG 0x8227
#define GL_RGBA 0x1908
#define GL_RGBA_INTEGER 0x8D99
#define GL_UNSIGNED_INT_2_10_10_10_REV 0x8368
#define GL_TEXTURE_BINDING_2D 0x8069
#define GL_FRAMEBUFFER_COMPLETE 0x8CD5
#define GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE 0x8CD0
#define GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME 0x8CD1
#define GL_COLOR_ATTACHMENT0 0x8CE0
#define GL_DEPTH_ATTACHMENT 0x8D00
#define GL_TEXTURE 0x1702

typedef void (*PFN_TexImage2D)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void*);
typedef void (*PFN_TexStorage2D)(GLenum, GLsizei, GLenum, GLsizei, GLsizei);
typedef void (*PFN_RenderbufferStorage)(GLenum, GLenum, GLsizei, GLsizei);
typedef void (*PFN_RenderbufferStorageMultisample)(GLenum, GLsizei, GLenum, GLsizei, GLsizei);
typedef GLenum (*PFN_CheckFramebufferStatus)(GLenum);
typedef void (*PFN_GetIntegerv)(GLenum, GLint*);
typedef void (*PFN_GetFramebufferAttachmentParameteriv)(GLenum, GLenum, GLenum, GLint*);

static PFN_TexImage2D real_TexImage2D;
static PFN_TexStorage2D real_TexStorage2D;
static PFN_RenderbufferStorage real_RenderbufferStorage;
static PFN_RenderbufferStorageMultisample real_RenderbufferStorageMultisample;
static PFN_CheckFramebufferStatus real_CheckFramebufferStatus;
static PFN_GetIntegerv real_GetIntegerv;
static PFN_GetFramebufferAttachmentParameteriv real_GetFramebufferAttachmentParameteriv;

// A format ES can render to, plus the format / type to allocate it with
typedef struct { GLenum from, to, format, type; } FormatSwap;

static const FormatSwap SWAPS[] = {
    {0x881B /* RGB16F */,       0x881A /* RGBA16F */,      GL_RGBA, GL_FLOAT},
    {0x8815 /* RGB32F */,       0x8814 /* RGBA32F */,      GL_RGBA, GL_FLOAT},
    {0x8054 /* RGB16 */,        0x881A /* RGBA16F */,      GL_RGBA, GL_FLOAT},
    {0x805B /* RGBA16 */,       0x881A /* RGBA16F */,      GL_RGBA, GL_FLOAT},
    {0x822A /* R16 */,          0x822D /* R16F */,         GL_RED,  GL_FLOAT},
    {0x822C /* RG16 */,         0x822F /* RG16F */,        GL_RG,   GL_FLOAT},
    {0x8053 /* RGB12 */,        0x881A /* RGBA16F */,      GL_RGBA, GL_FLOAT},
    {0x805A /* RGBA12 */,       0x881A /* RGBA16F */,      GL_RGBA, GL_FLOAT},
    {0x8C3D /* RGB9_E5 */,      0x881A /* RGBA16F */,      GL_RGBA, GL_FLOAT},
    {0x8F94 /* R8_SNORM */,     0x822D /* R16F */,         GL_RED,  GL_FLOAT},
    {0x8F95 /* RG8_SNORM */,    0x822F /* RG16F */,        GL_RG,   GL_FLOAT},
    {0x8F96 /* RGB8_SNORM */,   0x881A /* RGBA16F */,      GL_RGBA, GL_FLOAT},
    {0x8F97 /* RGBA8_SNORM */,  0x881A /* RGBA16F */,      GL_RGBA, GL_FLOAT},
    {0x8F98 /* R16_SNORM */,    0x822D /* R16F */,         GL_RED,  GL_FLOAT},
    {0x8F99 /* RG16_SNORM */,   0x822F /* RG16F */,        GL_RG,   GL_FLOAT},
    {0x8F9A /* RGB16_SNORM */,  0x881A /* RGBA16F */,      GL_RGBA, GL_FLOAT},
    {0x8F9B /* RGBA16_SNORM */, 0x881A /* RGBA16F */,      GL_RGBA, GL_FLOAT},
    {0x8052 /* RGB10 */,        0x8059 /* RGB10_A2 */,     GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV},
    {0x8C41 /* SRGB8 */,        0x8C43 /* SRGB8_ALPHA8 */, GL_RGBA, GL_UNSIGNED_BYTE},
    {0x8D8F /* RGB8I */,        0x8D8E /* RGBA8I */,       GL_RGBA_INTEGER, 0x1400},
    {0x8D7D /* RGB8UI */,       0x8D7C /* RGBA8UI */,      GL_RGBA_INTEGER, 0x1401},
    {0x8D89 /* RGB16I */,       0x8D88 /* RGBA16I */,      GL_RGBA_INTEGER, 0x1402},
    {0x8D77 /* RGB16UI */,      0x8D76 /* RGBA16UI */,     GL_RGBA_INTEGER, 0x1403},
    {0x8D83 /* RGB32I */,       0x8D82 /* RGBA32I */,      GL_RGBA_INTEGER, 0x1404},
    {0x8D71 /* RGB32UI */,      0x8D70 /* RGBA32UI */,     GL_RGBA_INTEGER, 0x1405},
};

// ONA: OpenGL ES only allocates a sized format with its own format / type pair. Shader pack
// loaders allocate empty render targets with desktop leftovers (Iris: RGBA16F or R11F_G11F_B10F with
// GL_UNSIGNED_BYTE), which ES rejects, so the texture stays empty and the framebuffer is
// incomplete (36054). For empty allocations the pair is set to one ES accepts.
typedef struct { GLenum internal, format, type; } FormatPair;

static const FormatPair ES_PAIRS[] = {
    {0x8058 /* RGBA8 */,          GL_RGBA, GL_UNSIGNED_BYTE},
    {0x8051 /* RGB8 */,           0x1907 /* RGB */, GL_UNSIGNED_BYTE},
    {0x8229 /* R8 */,             GL_RED, GL_UNSIGNED_BYTE},
    {0x822B /* RG8 */,            GL_RG, GL_UNSIGNED_BYTE},
    {0x8C43 /* SRGB8_ALPHA8 */,   GL_RGBA, GL_UNSIGNED_BYTE},
    {0x8D62 /* RGB565 */,         0x1907, GL_UNSIGNED_BYTE},
    {0x8056 /* RGBA4 */,          GL_RGBA, GL_UNSIGNED_BYTE},
    {0x8057 /* RGB5_A1 */,        GL_RGBA, GL_UNSIGNED_BYTE},
    {0x8059 /* RGB10_A2 */,       GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV},
    {0x906F /* RGB10_A2UI */,     GL_RGBA_INTEGER, GL_UNSIGNED_INT_2_10_10_10_REV},
    {0x881A /* RGBA16F */,        GL_RGBA, GL_FLOAT},
    {0x822D /* R16F */,           GL_RED, GL_FLOAT},
    {0x822F /* RG16F */,          GL_RG, GL_FLOAT},
    {0x8814 /* RGBA32F */,        GL_RGBA, GL_FLOAT},
    {0x822E /* R32F */,           GL_RED, GL_FLOAT},
    {0x8230 /* RG32F */,          GL_RG, GL_FLOAT},
    {0x8C3A /* R11F_G11F_B10F */, 0x1907, GL_FLOAT},
    {0x8231 /* R8I */,   0x8D94 /* RED_INTEGER */, 0x1400}, {0x8232 /* R8UI */,   0x8D94, 0x1401},
    {0x8233 /* R16I */,  0x8D94, 0x1402},                   {0x8234 /* R16UI */,  0x8D94, 0x1403},
    {0x8235 /* R32I */,  0x8D94, 0x1404},                   {0x8236 /* R32UI */,  0x8D94, 0x1405},
    {0x8237 /* RG8I */,  0x8228 /* RG_INTEGER */, 0x1400},  {0x8238 /* RG8UI */,  0x8228, 0x1401},
    {0x8239 /* RG16I */, 0x8228, 0x1402},                   {0x823A /* RG16UI */, 0x8228, 0x1403},
    {0x823B /* RG32I */, 0x8228, 0x1404},                   {0x823C /* RG32UI */, 0x8228, 0x1405},
    {0x8D8E /* RGBA8I */,  GL_RGBA_INTEGER, 0x1400},        {0x8D7C /* RGBA8UI */,  GL_RGBA_INTEGER, 0x1401},
    {0x8D88 /* RGBA16I */, GL_RGBA_INTEGER, 0x1402},        {0x8D76 /* RGBA16UI */, GL_RGBA_INTEGER, 0x1403},
    {0x8D82 /* RGBA32I */, GL_RGBA_INTEGER, 0x1404},        {0x8D70 /* RGBA32UI */, GL_RGBA_INTEGER, 0x1405},
    {0x81A5 /* DEPTH_COMPONENT16 */,  0x1902 /* DEPTH_COMPONENT */, 0x1405 /* UNSIGNED_INT */},
    {0x81A6 /* DEPTH_COMPONENT24 */,  0x1902, 0x1405},
    {0x8CAC /* DEPTH_COMPONENT32F */, 0x1902, GL_FLOAT},
    {0x88F0 /* DEPTH24_STENCIL8 */,   0x84F9 /* DEPTH_STENCIL */, 0x84FA /* UNSIGNED_INT_24_8 */},
    {0x8CAD /* DEPTH32F_STENCIL8 */,  0x84F9, 0x8DAD /* FLOAT_32_UNSIGNED_INT_24_8_REV */},
};

static const FormatPair* find_pair(GLenum internalformat) {
    for (size_t i = 0; i < sizeof(ES_PAIRS) / sizeof(ES_PAIRS[0]); i++) {
        if (ES_PAIRS[i].internal == internalformat) return &ES_PAIRS[i];
    }
    return NULL;
}

#define GL_PIXEL_UNPACK_BUFFER_BINDING 0x88EF

/** pixels == NULL is an empty allocation only while no unpack buffer is bound */
static int is_empty_allocation(const void* pixels) {
    if (pixels != NULL) return 0;
    if (real_GetIntegerv == NULL) return 1;
    GLint unpack = 0;
    real_GetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &unpack);
    return unpack == 0;
}

static int pairs_logged = 0;

static const FormatSwap* find_swap(GLenum internalformat) {
    for (size_t i = 0; i < sizeof(SWAPS) / sizeof(SWAPS[0]); i++) {
        if (SWAPS[i].from == internalformat) return &SWAPS[i];
    }
    return NULL;
}

static int swaps_logged = 0;
static void log_swap(const char* where, GLenum from, GLenum to) {
    if (swaps_logged >= 32) return;
    swaps_logged++;
    fprintf(stderr, "[ONA GLFix] %s: render target format 0x%X -> 0x%X (OpenGL ES can draw into it)\n", where, from, to);
    fflush(stderr);
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s: 0x%X -> 0x%X", where, from, to);
}

// Texture name -> format it was asked for and the one it got, for the incomplete framebuffer log
#define TRACKED 256
static struct { GLuint name; GLenum asked, used; } tracked[TRACKED];
static int tracked_next = 0;

static void track_bound_texture(GLenum asked, GLenum used) {
    if (real_GetIntegerv == NULL) return;
    GLint name = 0;
    real_GetIntegerv(GL_TEXTURE_BINDING_2D, &name);
    if (name <= 0) return;
    for (int i = 0; i < TRACKED; i++) {
        if (tracked[i].name == (GLuint) name) {
            tracked[i].asked = asked;
            tracked[i].used = used;
            return;
        }
    }
    tracked[tracked_next].name = (GLuint) name;
    tracked[tracked_next].asked = asked;
    tracked[tracked_next].used = used;
    tracked_next = (tracked_next + 1) % TRACKED;
}

static void ona_TexImage2D(GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height,
                           GLint border, GLenum format, GLenum type, const void* pixels) {
    const int empty = is_empty_allocation(pixels);
    const FormatSwap* swap = empty ? find_swap((GLenum) internalformat) : NULL;
    GLenum used = swap != NULL ? swap->to : (GLenum) internalformat;
    if (swap != NULL) {
        log_swap("glTexImage2D", (GLenum) internalformat, swap->to);
        format = swap->format;
        type = swap->type;
    }
    if (empty) {
        const FormatPair* pair = find_pair(used);
        if (pair != NULL && (pair->format != format || pair->type != type)) {
            if (pairs_logged < 16) {
                pairs_logged++;
                fprintf(stderr, "[ONA GLFix] glTexImage2D: format 0x%X allocated with 0x%X/0x%X instead of 0x%X/0x%X\n",
                        used, pair->format, pair->type, format, type);
                fflush(stderr);
            }
            format = pair->format;
            type = pair->type;
        }
    }
    real_TexImage2D(target, level, (GLint) used, width, height, border, format, type, empty ? NULL : pixels);
    if (level == 0 && target == 0x0DE1 /* GL_TEXTURE_2D */) {
        track_bound_texture((GLenum) internalformat, used);
    }
}

static void ona_TexStorage2D(GLenum target, GLsizei levels, GLenum internalformat, GLsizei width, GLsizei height) {
    const FormatSwap* swap = find_swap(internalformat);
    if (swap != NULL) log_swap("glTexStorage2D", internalformat, swap->to);
    GLenum used = swap != NULL ? swap->to : internalformat;
    real_TexStorage2D(target, levels, used, width, height);
    if (target == 0x0DE1) track_bound_texture(internalformat, used);
}

static void ona_RenderbufferStorage(GLenum target, GLenum internalformat, GLsizei width, GLsizei height) {
    const FormatSwap* swap = find_swap(internalformat);
    if (swap != NULL) log_swap("glRenderbufferStorage", internalformat, swap->to);
    real_RenderbufferStorage(target, swap != NULL ? swap->to : internalformat, width, height);
}

static void ona_RenderbufferStorageMultisample(GLenum target, GLsizei samples, GLenum internalformat, GLsizei width, GLsizei height) {
    const FormatSwap* swap = find_swap(internalformat);
    if (swap != NULL) log_swap("glRenderbufferStorageMultisample", internalformat, swap->to);
    real_RenderbufferStorageMultisample(target, samples, swap != NULL ? swap->to : internalformat, width, height);
}

static int incomplete_logged = 0;

static void log_attachment(GLenum target, GLenum attachment, const char* label) {
    GLint type = 0, name = 0;
    real_GetFramebufferAttachmentParameteriv(target, attachment, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE, &type);
    if (type == 0) return;
    real_GetFramebufferAttachmentParameteriv(target, attachment, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, &name);
    GLenum asked = 0, used = 0;
    if (type == GL_TEXTURE) {
        for (int i = 0; i < TRACKED; i++) {
            if (tracked[i].name == (GLuint) name && name > 0) {
                asked = tracked[i].asked;
                used = tracked[i].used;
                break;
            }
        }
    }
    fprintf(stderr, "[ONA GLFix]   %s: %s %d, format asked 0x%X, used 0x%X\n", label,
            type == GL_TEXTURE ? "texture" : "renderbuffer", name, asked, used);
}

static GLenum ona_CheckFramebufferStatus(GLenum target) {
    GLenum status = real_CheckFramebufferStatus(target);
    if (status != GL_FRAMEBUFFER_COMPLETE && status != 0 && incomplete_logged < 8) {
        incomplete_logged++;
        fprintf(stderr, "[ONA GLFix] Framebuffer incomplete, status %u (0x%X):\n", status, status);
        if (real_GetFramebufferAttachmentParameteriv != NULL) {
            char label[24];
            for (int i = 0; i < 8; i++) {
                snprintf(label, sizeof(label), "colortex attachment %d", i);
                log_attachment(target, GL_COLOR_ATTACHMENT0 + i, label);
            }
            log_attachment(target, GL_DEPTH_ATTACHMENT, "depth");
        }
        fflush(stderr);
        __android_log_print(ANDROID_LOG_WARN, TAG, "Framebuffer incomplete: 0x%X", status);
    }
    return status;
}

// ---------------------------------------------------------------------------------------------
// Fragment output arrays (Minecraft 26.3 order independent transparency, oit_add_transmittance):
//   layout(location = 0) out vec4 coeff[OIT_COEFF_ATTACHMENT_COUNT];
//   for (...) coeff[attachmentIndex][i] = ...;
// Desktop OpenGL accepts the loop index, OpenGL ES drivers (Mali: "S0015: Outputs declared as arrays
// may only be indexed by a constant integral expression") refuse it, every transparency pipeline
// fails and the game stays black after the loading screen. The array becomes a plain global the
// shader writes as before, and a new main() copies each element to its own output with a constant
// index after the original main ran. Same locations, same values.
// ---------------------------------------------------------------------------------------------

typedef char GLchar;
typedef void (*PFN_ShaderSource)(GLuint, GLsizei, const GLchar* const*, const GLint*);
typedef void (*PFN_GetShaderiv)(GLuint, GLenum, GLint*);
static PFN_ShaderSource real_ShaderSource;
static PFN_GetShaderiv real_GetShaderiv;

#define GL_SHADER_TYPE 0x8B4F
#define GL_FRAGMENT_SHADER 0x8B30
#define AOUT_MAX_ARRAYS 4
#define AOUT_MAX_ELEMENTS 8

typedef struct {
    size_t start, end;      // the declaration line in the source (end: after its newline)
    char type[32], name[64], size[64];
    int location;
    int literal;            // size is a number (else a macro)
} ArrayOut;

typedef struct { char* data; size_t len, cap; } Buf;

static int buf_add(Buf* b, const char* s, size_t n) {
    if (b->len + n + 1 > b->cap) {
        size_t cap = (b->cap ? b->cap * 2 : 4096);
        while (cap < b->len + n + 1) cap *= 2;
        char* data = realloc(b->data, cap);
        if (!data) return 0;
        b->data = data;
        b->cap = cap;
    }
    memcpy(b->data + b->len, s, n);
    b->len += n;
    b->data[b->len] = 0;
    return 1;
}

static int buf_printf(Buf* b, const char* fmt, ...) __attribute__((format(printf, 2, 3)));
static int buf_printf(Buf* b, const char* fmt, ...) {
    char tmp[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(tmp, sizeof(tmp), fmt, ap);
    va_end(ap);
    if (n < 0 || n >= (int) sizeof(tmp)) return 0;
    return buf_add(b, tmp, (size_t) n);
}

static const char* skip_spaces(const char* p, const char* end) {
    while (p < end && (*p == ' ' || *p == '\t')) p++;
    return p;
}

static const char* read_ident(const char* p, const char* end, char* out, size_t cap) {
    size_t n = 0;
    if (p >= end || !(isalpha((unsigned char) *p) || *p == '_')) return NULL;
    while (p < end && (isalnum((unsigned char) *p) || *p == '_')) {
        if (n + 1 >= cap) return NULL;
        out[n++] = *p++;
    }
    out[n] = 0;
    return p;
}

static int is_word(const char* src, size_t len, size_t at, const char* word) {
    size_t w = strlen(word);
    if (at + w > len || strncmp(src + at, word, w) != 0) return 0;
    if (at > 0 && (isalnum((unsigned char) src[at - 1]) || src[at - 1] == '_')) return 0;
    if (at + w < len && (isalnum((unsigned char) src[at + w]) || src[at + w] == '_')) return 0;
    return 1;
}

/** One line: [layout(... location = N ...)] out [precision] TYPE NAME[SIZE]; */
static int parse_array_out(const char* line, const char* end, ArrayOut* out) {
    const char* p = skip_spaces(line, end);
    char word[64];
    out->location = -1;
    if (end - p > 6 && strncmp(p, "layout", 6) == 0) {
        p = skip_spaces(p + 6, end);
        if (p >= end || *p != '(') return 0;
        const char* close = memchr(p, ')', (size_t) (end - p));
        if (!close) return 0;
        const char* loc = p;
        while (loc < close) {
            if (close - loc > 8 && strncmp(loc, "location", 8) == 0) {
                const char* q = skip_spaces(loc + 8, close);
                if (q < close && *q == '=') {
                    q = skip_spaces(q + 1, close);
                    out->location = atoi(q);
                }
                break;
            }
            loc++;
        }
        p = skip_spaces(close + 1, end);
    }
    if (out->location < 0) return 0; // without an explicit location the outputs could not keep theirs
    p = read_ident(p, end, word, sizeof(word));
    if (!p || strcmp(word, "out") != 0) return 0;
    p = skip_spaces(p, end);
    p = read_ident(p, end, out->type, sizeof(out->type));
    if (!p) return 0;
    if (!strcmp(out->type, "highp") || !strcmp(out->type, "mediump") || !strcmp(out->type, "lowp")) {
        p = read_ident(skip_spaces(p, end), end, out->type, sizeof(out->type));
        if (!p) return 0;
    }
    p = read_ident(skip_spaces(p, end), end, out->name, sizeof(out->name));
    if (!p) return 0;
    p = skip_spaces(p, end);
    if (p >= end || *p != '[') return 0;
    p = skip_spaces(p + 1, end);
    const char* size_start = p;
    while (p < end && *p != ']') p++;
    if (p >= end) return 0;
    const char* size_end = p;
    while (size_end > size_start && (size_end[-1] == ' ' || size_end[-1] == '\t')) size_end--;
    size_t size_len = (size_t) (size_end - size_start);
    if (size_len == 0 || size_len >= sizeof(out->size)) return 0;
    memcpy(out->size, size_start, size_len);
    out->size[size_len] = 0;
    p = skip_spaces(p + 1, end);
    if (p >= end || *p != ';') return 0;
    out->literal = 1;
    for (size_t i = 0; i < size_len; i++) if (!isdigit((unsigned char) out->size[i])) out->literal = 0;
    return 1;
}

static int has_define(const char* src, const char* name) {
    const char* p = src;
    size_t n = strlen(name);
    while ((p = strstr(p, "#define")) != NULL) {
        const char* q = p + 7;
        while (*q == ' ' || *q == '\t') q++;
        if (strncmp(q, name, n) == 0 && !(isalnum((unsigned char) q[n]) || q[n] == '_')) return 1;
        p = q;
    }
    return 0;
}

static int aout_logged;

/** @return a rewritten copy (to free) or NULL when the source needs no change */
static char* rewrite_array_outputs(const char* src, size_t len) {
    if (!strstr(src, " out ") && !strstr(src, "\tout ") && !strstr(src, ")out ")) return NULL;
    ArrayOut found[AOUT_MAX_ARRAYS];
    int count = 0;
    size_t line = 0;
    while (line < len && count < AOUT_MAX_ARRAYS) {
        const char* nl = memchr(src + line, '\n', len - line);
        size_t line_end = nl ? (size_t) (nl - src) : len;
        ArrayOut a;
        memset(&a, 0, sizeof(a));
        if (parse_array_out(src + line, src + line_end, &a)
                && (a.literal || has_define(src, a.size))
                && (!a.literal || (atoi(a.size) > 0 && atoi(a.size) <= AOUT_MAX_ELEMENTS))) {
            a.start = line;
            a.end = nl ? line_end + 1 : line_end;
            found[count++] = a;
        }
        line = nl ? line_end + 1 : len;
    }
    if (count == 0) return NULL;

    // main() of the shader: renamed, the new main() calls it and copies the outputs
    size_t main_at = (size_t) -1;
    for (size_t i = 0; i + 4 < len; i++) {
        if (src[i] == 'm' && is_word(src, len, i, "main")) {
            size_t j = i + 4;
            while (j < len && (src[j] == ' ' || src[j] == '\t')) j++;
            if (j >= len || src[j] != '(') continue;
            size_t k = i;
            while (k > 0 && (src[k - 1] == ' ' || src[k - 1] == '\t')) k--;
            if (k >= 4 && is_word(src, len, k - 4, "void")) {
                main_at = i;
                break;
            }
        }
    }
    if (main_at == (size_t) -1) return NULL;

    Buf b = {0};
    size_t pos = 0;
    int ok = 1;
    for (int n = 0; n < count && ok; n++) {
        ArrayOut* a = &found[n];
        if (main_at >= pos && main_at < a->start) {
            ok &= buf_add(&b, src + pos, main_at - pos) && buf_add(&b, "ona_aout_main", strlen("ona_aout_main"));
            pos = main_at + 4;
        }
        ok &= buf_add(&b, src + pos, a->start - pos);
        ok &= buf_printf(&b, "%s %s[%s];\n#define ONA_AOUT_%d 1\n", a->type, a->name, a->size, n);
        int elements = a->literal ? atoi(a->size) : AOUT_MAX_ELEMENTS;
        for (int k = 0; k < elements && ok; k++) {
            if (!a->literal) ok &= buf_printf(&b, "#if (%s) > %d\n", a->size, k);
            ok &= buf_printf(&b, "layout(location = %d) out %s ona_aout_%d_%d;\n", a->location + k, a->type, n, k);
            if (!a->literal) ok &= buf_add(&b, "#endif\n", strlen("#endif\n"));
        }
        pos = a->end;
    }
    if (ok && main_at >= pos) {
        ok &= buf_add(&b, src + pos, main_at - pos) && buf_add(&b, "ona_aout_main", strlen("ona_aout_main"));
        pos = main_at + 4;
    }
    ok &= buf_add(&b, src + pos, len - pos);
    ok &= buf_add(&b, "\nvoid main() {\n    ona_aout_main();\n", strlen("\nvoid main() {\n    ona_aout_main();\n"));
    for (int n = 0; n < count && ok; n++) {
        ArrayOut* a = &found[n];
        ok &= buf_printf(&b, "#ifdef ONA_AOUT_%d\n", n);
        int elements = a->literal ? atoi(a->size) : AOUT_MAX_ELEMENTS;
        for (int k = 0; k < elements && ok; k++) {
            if (!a->literal) ok &= buf_printf(&b, "#if (%s) > %d\n", a->size, k);
            ok &= buf_printf(&b, "    ona_aout_%d_%d = %s[%d];\n", n, k, a->name, k);
            if (!a->literal) ok &= buf_add(&b, "#endif\n", strlen("#endif\n"));
        }
        ok &= buf_add(&b, "#endif\n", strlen("#endif\n"));
    }
    ok &= buf_add(&b, "}\n", strlen("}\n"));
    if (!ok) {
        free(b.data);
        return NULL;
    }
    if (aout_logged < 8) {
        aout_logged++;
        fprintf(stderr, "[ONA GLFix] fragment output array %s[%s] split into separate outputs\n",
                found[0].name, found[0].size);
        fflush(stderr);
    }
    return b.data;
}

static void ona_ShaderSource(GLuint shader, GLsizei count, const GLchar* const* string, const GLint* length) {
    if (count <= 0 || string == NULL) {
        real_ShaderSource(shader, count, string, length);
        return;
    }
    GLint type = 0;
    if (real_GetShaderiv) real_GetShaderiv(shader, GL_SHADER_TYPE, &type);
    // Join the pieces: the declaration and main() can be in different strings
    Buf joined = {0};
    int ok = 1;
    for (GLsizei i = 0; i < count && ok; i++) {
        if (string[i] == NULL) continue;
        size_t n = (length != NULL && length[i] >= 0) ? (size_t) length[i] : strlen(string[i]);
        ok &= buf_add(&joined, string[i], n);
    }
    char* rewritten = NULL;
    if (ok && joined.data != NULL) {
        int fragment = type == GL_FRAGMENT_SHADER || (type == 0 && strstr(joined.data, "gl_Position") == NULL);
        if (fragment) rewritten = rewrite_array_outputs(joined.data, joined.len);
    }
    if (rewritten != NULL) {
        const GLchar* one = rewritten;
        real_ShaderSource(shader, 1, &one, NULL);
        free(rewritten);
    } else {
        real_ShaderSource(shader, count, string, length);
    }
    free(joined.data);
}

/**
 * Called by PojavRendererInit for the GL functions it wraps.
 * @return the wrapper's address, or 0 to keep the renderer's own function
 */
JNIEXPORT jlong JNICALL
Java_org_lwjgl_opengl_PojavRendererInit_nativeWrapGlFunction(JNIEnv *env, jclass clazz, jstring jname, jlong real) {
    if (real == 0) return 0;
    const char* name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (name == NULL) return 0;
    void* wrapper = NULL;
    void* address = (void*) (intptr_t) real;
    if (strcmp(name, "glTexImage2D") == 0) {
        real_TexImage2D = (PFN_TexImage2D) address;
        wrapper = (void*) ona_TexImage2D;
    } else if (strcmp(name, "glTexStorage2D") == 0) {
        real_TexStorage2D = (PFN_TexStorage2D) address;
        wrapper = (void*) ona_TexStorage2D;
    } else if (strcmp(name, "glRenderbufferStorage") == 0) {
        real_RenderbufferStorage = (PFN_RenderbufferStorage) address;
        wrapper = (void*) ona_RenderbufferStorage;
    } else if (strcmp(name, "glRenderbufferStorageMultisample") == 0) {
        real_RenderbufferStorageMultisample = (PFN_RenderbufferStorageMultisample) address;
        wrapper = (void*) ona_RenderbufferStorageMultisample;
    } else if (strcmp(name, "glCheckFramebufferStatus") == 0) {
        real_CheckFramebufferStatus = (PFN_CheckFramebufferStatus) address;
        wrapper = (void*) ona_CheckFramebufferStatus;
    } else if (strcmp(name, "glGetIntegerv") == 0) {
        // only needed by the wrappers, the game keeps calling the renderer directly
        real_GetIntegerv = (PFN_GetIntegerv) address;
    } else if (strcmp(name, "glShaderSource") == 0) {
        real_ShaderSource = (PFN_ShaderSource) address;
        wrapper = (void*) ona_ShaderSource;
    } else if (strcmp(name, "glGetShaderiv") == 0) {
        // only needed by the wrapper (shader type), the game keeps calling the renderer directly
        real_GetShaderiv = (PFN_GetShaderiv) address;
    } else if (strcmp(name, "glGetFramebufferAttachmentParameteriv") == 0) {
        real_GetFramebufferAttachmentParameteriv = (PFN_GetFramebufferAttachmentParameteriv) address;
    }
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return (jlong) (intptr_t) wrapper;
}
