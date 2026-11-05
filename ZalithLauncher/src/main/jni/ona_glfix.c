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
    } else if (strcmp(name, "glGetFramebufferAttachmentParameteriv") == 0) {
        real_GetFramebufferAttachmentParameteriv = (PFN_GetFramebufferAttachmentParameteriv) address;
    }
    (*env)->ReleaseStringUTFChars(env, jname, name);
    return (jlong) (intptr_t) wrapper;
}
