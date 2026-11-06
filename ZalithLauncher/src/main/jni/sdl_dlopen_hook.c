// SDL JNI_OnLoad isolation for the embedded game JVM.
// Reference: https://github.com/AngelAuraMC/Amethyst-Android/commit/e4c79084d

#include <dlfcn.h>
#include <jni.h>
#include <stdbool.h>
#include <bytehook.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "environ/environ.h"
#include "logger/logger.h"
#include "sdl_hook.h"
#include "utils.h"

typedef void *(*dlsym_func_t)(void *handle, const char *symbol);
typedef jint (*jni_on_load_func_t)(JavaVM *vm, void *reserved);

typedef bytehook_stub_t (*bytehook_hook_all_fn)(const char *callee_path_name, const char *symbol,
                                                  void *new_func, bytehook_hooked_t hooked, void *hooked_arg);

static void *sdlHandle;
static jni_on_load_func_t originalSdlJniOnLoad;

static jint isolatedSdlJniOnLoad(JavaVM *vm, void *reserved) {
    if (originalSdlJniOnLoad != NULL && pojav_environ->dalvikJavaVMPtr == vm) {
        return originalSdlJniOnLoad(vm, reserved);
    }
    return JNI_VERSION_1_4;
}

// ONA: Minecraft 26.x compares the GL/Vulkan entry points SDL resolves with the ones LWJGL uses
// ("glGetError mismatch" / "vkGetInstanceProcAddr mismatch"). SDL resolves them through its
// internal SDL_LoadFunction -> dlsym, which the SDL_LoadObject hooks can't see, so fix it here.
extern void *maybe_load_vulkan();

typedef void *(*egl_get_proc_address_t)(const char *name);
static egl_get_proc_address_t realEglGetProcAddress;
static void *glWrapperHandle;

static bool callerIsSdl(void *returnAddress) {
    Dl_info info;
    if (returnAddress == NULL || !dladdr(returnAddress, &info) || info.dli_fname == NULL) return false;
    return strstr(info.dli_fname, "libSDL3") != NULL;
}

// LWJGL takes gl* from the renderer library (-Dorg.lwjgl.opengl.libname), SDL asks the EGL.
// ONA: same lookup order as LWJGL's GL.create(): the library's own glXGetProcAddress(ARB) /
// eglGetProcAddress / OSMesaGetProcAddress first, its exported symbol after. Mesa (Kopper Zink's
// libglxshim) answers both, with different pointers, so a plain dlsym gave Minecraft 26.x
// "glGetError mismatch" with Voxy.
// Minecraft 26.x calls SDL_GL_LoadLibrary(<LWJGL's GL library path>) and then compares
// SDL_GL_GetProcAddress("glGetError") with LWJGL's. That path is the library both must agree on.
typedef bool (*sdl_gl_load_library_t)(const char *path);
static sdl_gl_load_library_t realSdlGlLoadLibrary;
static void *sdlGlLibraryHandle;

static bool onaSdlGlLoadLibrary(const char *path) {
    if (path != NULL && path[0] != '\0') {
        void *handle = dlopen(path, RTLD_NOW | RTLD_NOLOAD);
        if (handle == NULL) handle = dlopen(path, RTLD_NOW);
        if (handle != NULL) {
            sdlGlLibraryHandle = handle;
            LOG_TO_I("SDL_Hook: SDL_GL_LoadLibrary(%s): gl* lookups follow LWJGL on this library", path);
        }
    }
    // SDL tries the path it is given as its EGL library first. For desktop GL renderers that path
    // is the GL library (Kopper Zink's libglxshim, which carries EGL entry points of its own), and that
    // EGL refuses SDL's desktop config ("EGL_BAD_ATTRIBUTE") and the OpenGL API ("Could not bind EGL
    // API"). Hand SDL the renderer's real EGL, the one ONA's own bridge uses; gl* lookups still go to
    // the GL library through the eglGetProcAddress wrapper above.
    const char *renderer = getenv("POJAV_RENDERER");
    const char *egl = getenv("SDL_EGL_LIBRARY");
    if (egl == NULL || egl[0] == '\0') egl = getenv("POJAVEXEC_EGL");
    if (renderer != NULL && strstr(renderer, "desktopgl") != NULL && egl != NULL && egl[0] != '\0' &&
        (path == NULL || strcmp(path, egl) != 0)) {
        LOG_TO_I("SDL_Hook: SDL_GL_LoadLibrary uses the renderer's EGL %s", egl);
        return realSdlGlLoadLibrary != NULL ? realSdlGlLoadLibrary(egl) : false;
    }
    return realSdlGlLoadLibrary != NULL ? realSdlGlLoadLibrary(path) : false;
}

typedef void *(*lib_get_proc_t)(const char *name);
static lib_get_proc_t libGetProc;
static void *libGetProcHandle;

static void *lwjglStyleLookup(void *library, const char *name) {
    if (library == NULL) return NULL;
    if (library != libGetProcHandle) {
        libGetProcHandle = library;
        libGetProc = NULL;
        static const char *const getters[] = {
            "glXGetProcAddress", "glXGetProcAddressARB", "eglGetProcAddress", "OSMesaGetProcAddress"
        };
        for (size_t i = 0; i < sizeof(getters) / sizeof(getters[0]) && libGetProc == NULL; i++) {
            libGetProc = (lib_get_proc_t) dlsym(library, getters[i]);
            if (libGetProc != NULL) LOG_TO_I("SDL_Hook: gl* lookups use %s of the renderer library, like LWJGL", getters[i]);
        }
    }
    void *func = libGetProc != NULL ? libGetProc(name) : NULL;
    return func != NULL ? func : dlsym(library, name);
}

static void *onaSdlEglGetProcAddress(const char *name) {
    if (name != NULL && strncmp(name, "gl", 2) == 0) {
        if (glWrapperHandle == NULL) {
            const char *glLibrary = getenv("ONA_GL_LIBRARY");
            if (glLibrary != NULL && glLibrary[0] != '\0') {
                glWrapperHandle = dlopen(glLibrary, RTLD_NOW | RTLD_NOLOAD);
                if (glWrapperHandle == NULL) glWrapperHandle = dlopen(glLibrary, RTLD_NOW);
            }
        }
        if (glWrapperHandle != NULL || sdlGlLibraryHandle != NULL) {
            void *wrapperFunc = lwjglStyleLookup(sdlGlLibraryHandle != NULL ? sdlGlLibraryHandle : glWrapperHandle, name);
            if (wrapperFunc != NULL) return wrapperFunc;
        }
    }
    return realEglGetProcAddress != NULL ? realEglGetProcAddress(name) : NULL;
}

static void *onaWrapSdlSymbol(void *handle, const char *symbol, void *result) {
    if (strcmp(symbol, "vkGetInstanceProcAddr") == 0) {
        // Same loader instance as LWJGL (possibly Turnip in a private namespace)
        void *launcherLoader = maybe_load_vulkan();
        if (launcherLoader == NULL || launcherLoader == handle) return result;
        void *launcherFunc = dlsym(launcherLoader, symbol);
        if (launcherFunc == NULL) return result;
        LOG_TO_I("SDL_Hook: giving SDL the launcher's vkGetInstanceProcAddr");
        return launcherFunc;
    }
    if (strcmp(symbol, "eglGetProcAddress") == 0) {
        const char *glLibrary = getenv("ONA_GL_LIBRARY");
        if (glLibrary == NULL || glLibrary[0] == '\0') return result;
        if (realEglGetProcAddress == NULL) realEglGetProcAddress = (egl_get_proc_address_t) result;
        LOG_TO_I("SDL_Hook: SDL gl* lookups go to %s", glLibrary);
        return (void *) onaSdlEglGetProcAddress;
    }
    return result;
}

// ONA: mods that bring their own SDL binding (Controlify 3.x on Minecraft 26.x, through Java's
// FFM) find the launcher's libSDL3.so, which is the very SDL instance the game draws and reads
// input with. Controlify then sets an SDL event filter that keeps only joystick added / removed
// events and drains the queue in SDL_PollEvent, so the game never sees a touch or mouse button
// again ("nothing on the title screen can be pressed"). For lookups that come from Java's
// FFM (libjvm / libjava, while the game's LWJGL binding looks up from liblwjgl) both calls get
// versions that only touch the joystick events the mod is after. The mod keeps working.
#define ONA_SDL_EVENT_JOYSTICK_ADDED 0x605
#define ONA_SDL_EVENT_JOYSTICK_REMOVED 0x606
#define ONA_SDL_GETEVENT 2

typedef int (*sdl_peep_events_t)(void *events, int numevents, int action, uint32_t minType, uint32_t maxType);
static sdl_peep_events_t realPeepEvents;
static bool modFilterLogged;

static bool callerIsJavaFfm(void *returnAddress) {
    Dl_info info;
    if (returnAddress == NULL || !dladdr(returnAddress, &info) || info.dli_fname == NULL) return false;
    return strstr(info.dli_fname, "libjvm.so") != NULL || strstr(info.dli_fname, "libjava.so") != NULL;
}

static void modSetEventFilter(void *filter, void *userdata) {
    (void) filter;
    (void) userdata;
    if (!modFilterLogged) {
        modFilterLogged = true;
        LOG_TO_I("SDL_Hook: a mod's SDL event filter is kept away from the game's input events");
    }
}

static bool modPollEvent(void *event) {
    if (realPeepEvents == NULL) {
        void *sdl = dlopen("libSDL3.so", RTLD_NOW | RTLD_NOLOAD);
        if (sdl != NULL) realPeepEvents = (sdl_peep_events_t) dlsym(sdl, "SDL_PeepEvents");
        if (realPeepEvents == NULL) return false;
    }
    if (event == NULL) return false;
    return realPeepEvents(event, 1, ONA_SDL_GETEVENT, ONA_SDL_EVENT_JOYSTICK_ADDED, ONA_SDL_EVENT_JOYSTICK_REMOVED) > 0;
}

static void *customDlsym(void *handle, const char *symbol) {
    void *result = BYTEHOOK_CALL_PREV(customDlsym, dlsym_func_t, handle, symbol);
    // SDL with EGL < 1.5 looks gl* up straight in the GL library: answer like LWJGL does
    bool sdlGlLookup = result != NULL && symbol != NULL && handle != NULL && handle == sdlGlLibraryHandle
            && strncmp(symbol, "gl", 2) == 0 && strncmp(symbol, "glX", 3) != 0
            && callerIsSdl(BYTEHOOK_RETURN_ADDRESS());
    bool modSdlCall = result != NULL && symbol != NULL
            && (strcmp(symbol, "SDL_SetEventFilter") == 0 || strcmp(symbol, "SDL_PollEvent") == 0)
            && callerIsJavaFfm(BYTEHOOK_RETURN_ADDRESS());
    bool fromSdl = result != NULL && symbol != NULL
            && (strcmp(symbol, "vkGetInstanceProcAddr") == 0 || strcmp(symbol, "eglGetProcAddress") == 0)
            && callerIsSdl(BYTEHOOK_RETURN_ADDRESS());
    BYTEHOOK_POP_STACK();
    if (fromSdl) result = onaWrapSdlSymbol(handle, symbol, result);
    if (sdlGlLookup) {
        void *same = lwjglStyleLookup(handle, symbol);
        if (same != NULL) result = same;
    }
    if (result != NULL && symbol != NULL && strcmp(symbol, "SDL_GL_LoadLibrary") == 0) {
        if (realSdlGlLoadLibrary == NULL) realSdlGlLoadLibrary = (sdl_gl_load_library_t) result;
        return (void *) onaSdlGlLoadLibrary;
    }
    if (modSdlCall) {
        Dl_info info;
        // only the game's own SDL instance needs protecting
        if (dladdr(result, &info) && info.dli_fname != NULL && strstr(info.dli_fname, "libSDL3") != NULL) {
            LOG_TO_I("SDL_Hook: %s looked up by a mod through Java FFM, game input stays with the game", symbol);
            return strcmp(symbol, "SDL_SetEventFilter") == 0 ? (void *) modSetEventFilter : (void *) modPollEvent;
        }
    }
    if (sdlHandle == NULL) {
        sdlHandle = dlopen("libSDL3.so", RTLD_LOCAL | RTLD_NOW);
    }
    if (sdlHandle != NULL && handle == sdlHandle && symbol != NULL && strcmp(symbol, "JNI_OnLoad") == 0) {
        originalSdlJniOnLoad = (jni_on_load_func_t) result;
        result = (void *) isolatedSdlJniOnLoad;
    }
    // LWJGL 的 org.lwjgl.sdl 绑定等消费方经 dlsym 解析 SDL 函数指针后直接调用，
    // hook_all 的 GOT 导入补丁拦截不到；在解析出口统一换成 sdl_hook 的 dlsym 层代理
    if (result != NULL && symbol != NULL) {
        void *proxy = sdlDlsymProxy(symbol, result);
        if (proxy != NULL) {
            LOG_TO_I("SDL_Hook: dlsym proxy for %s", symbol);
            result = proxy;
        }
    }
    return result;
}

void create_sdl_dlopen_hooks(bytehook_hook_all_fn hookAll) {
    if (hookAll == NULL) return;
    hookAll(NULL, "dlsym", (void *) customDlsym, NULL, NULL);
}