#include <jni.h>
#include <assert.h>
#include <dlfcn.h>

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <unistd.h>

#include <EGL/egl.h>
#include <GL/osmesa.h>
#include "ctxbridges/egl_loader.h"
#include "ctxbridges/osmesa_loader.h"
#include "ctxbridges/renderer_config.h"
#include "ctxbridges/virgl_bridge.h"
#include "driver_helper/nsbypass.h"

#ifdef GLES_TEST
#include <GLES2/gl2.h>
#endif

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/rect.h>
#include <string.h>
#include <environ/environ.h>
#include <android/dlext.h>
#include <time.h>
#include "utils.h"
#include "ctxbridges/bridge_tbl.h"
#include "ctxbridges/osm_bridge.h"

#define GLFW_CLIENT_API 0x22001
/* Consider GLFW_NO_API as Vulkan API */
#define GLFW_NO_API 0
#define GLFW_OPENGL_API 0x30001

// This means that the function is an external API and that it will be used
#define EXTERNAL_API __attribute__((used))
// This means that you are forced to have this function/variable for ABI compatibility
#define ABI_COMPAT __attribute__((unused))

EGLConfig config;
struct PotatoBridge potatoBridge;

// MC 最近一次请求的交换间隔；-1 表示尚未请求
static int lastSwapInterval = -1;

void* loadTurnipVulkan(const char* driver_path, const char* native_dir, const char* cache_dir);
void calculateFPS();

extern void updateMonitorSize(int width, int height);
extern jboolean ensureGlfwNativeBridgeInitialized(JNIEnv *env);

EXTERNAL_API void pojavTerminate() {
    printf("EGLBridge: Terminating\n");

    switch (pojav_environ->config_renderer) {
        case RENDERER_GL4ES: {
            EGLBoolean (*terminate_make_current)(EGLDisplay, EGLSurface, EGLSurface, EGLContext) =
                    sfpew_eglMakeCurrent_p != NULL ? sfpew_eglMakeCurrent_p : eglMakeCurrent_p;
            terminate_make_current(potatoBridge.eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            eglDestroySurface_p(potatoBridge.eglDisplay, potatoBridge.eglSurface);
            EGLBoolean (*terminate_destroy_context)(EGLDisplay, EGLContext) =
                    sfpew_eglDestroyContext_p != NULL ? sfpew_eglDestroyContext_p : eglDestroyContext_p;
            terminate_destroy_context(potatoBridge.eglDisplay, potatoBridge.eglContext);
            eglTerminate_p(potatoBridge.eglDisplay);
            eglReleaseThread_p();

            potatoBridge.eglContext = EGL_NO_CONTEXT;
            potatoBridge.eglDisplay = EGL_NO_DISPLAY;
            potatoBridge.eglSurface = EGL_NO_SURFACE;
        } break;

            //case RENDERER_VIRGL:
        case RENDERER_VK_ZINK: {
            // Nothing to do here
        } break;
    }
}

JNIEXPORT void JNICALL Java_com_movtery_zalithlauncher_bridge_ZLBridge_setupBridgeWindow(JNIEnv* env, ABI_COMPAT jclass clazz, jobject surface) {
    // 首个窗口由 pojavInit 应用交换间隔；此处处理窗口重建（旋转、分屏等）：
    // 生产者状态会随新窗口重置，若不重新应用，MC 不会再次发起交换间隔调用，帧率会退回锁定在屏幕刷新率
    bool windowRecreated = pojav_environ->pojavWindow != NULL;
    pojav_environ->pojavWindow = ANativeWindow_fromSurface(env, surface);
    if (windowRecreated && pojav_environ->config_renderer != RENDERER_VULKAN) {
        if (lastSwapInterval >= 0) setNativeWindowSwapInterval(pojav_environ->pojavWindow, lastSwapInterval);
        else if (!getenv("POJAV_VSYNC_IN_ZINK")) setNativeWindowSwapInterval(pojav_environ->pojavWindow, 0);
    }
    if (br_setup_window) br_setup_window();
}

JNIEXPORT void JNICALL
Java_com_movtery_zalithlauncher_bridge_ZLBridge_releaseBridgeWindow(ABI_COMPAT JNIEnv *env, ABI_COMPAT jclass clazz) {
    ANativeWindow_release(pojav_environ->pojavWindow);
}

EXTERNAL_API void* pojavGetCurrentContext() {
    if (pojav_environ->config_renderer == RENDERER_VIRGL)
        return virglGetCurrentContext();

    // SDL (26.x) creates the context itself, the bridge table may never have been set up
    if (br_get_current != NULL) return br_get_current();
    return eglGetCurrentContext_p != NULL ? eglGetCurrentContext_p() : NULL;
}

static void set_vulkan_ptr(void* ptr) {
    char envval[64];
    sprintf(envval, "%"PRIxPTR, (uintptr_t)ptr);
    setenv("VULKAN_PTR", envval, 1);
}

void load_vulkan() {
    const char* zinkPreferSystemDriver = getenv("POJAV_ZINK_PREFER_SYSTEM_DRIVER");
    int deviceApiLevel = android_get_device_api_level();
    if (zinkPreferSystemDriver == NULL && deviceApiLevel >= 28) {
#ifdef ADRENO_POSSIBLE
        const char* native_dir = getenv("DRIVER_PATH");
        const char* cache_dir = getenv("TMPDIR");

        void* result = loadTurnipVulkan(NULL, native_dir, cache_dir);
        if (result != NULL)
        {
            printf("AdrenoSupp: Loaded Turnip, loader address: %p\n", result);
            set_vulkan_ptr(result);
            return;
        }
#endif
    }

    printf("OSMDroid: Loading Vulkan regularly...\n");
    void* vulkanPtr = dlopen("libvulkan.so", RTLD_LAZY | RTLD_LOCAL);
    printf("OSMDroid: Loaded Vulkan, ptr=%p\n", vulkanPtr);
    set_vulkan_ptr(vulkanPtr);
}

int pojavInitOpenGL() {
    const char *renderer = getenv("POJAV_RENDERER");

    if (!strncmp("opengles", renderer, 8))
    {
        pojav_environ->config_renderer = RENDERER_GL4ES;
        if (!strcmp(renderer, "opengles3_desktopgl_zink_kopper")) {
            load_vulkan();
            setenv("GALLIUM_DRIVER", "zink", 1);
            setenv("MESA_ANDROID_NO_KMS_SWRAST", "1", 1);
        }
        set_gl_bridge_tbl();
    }

    if (!strcmp(renderer, "custom_gallium"))
    {
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        load_vulkan();
        set_osm_bridge_tbl();
    }

    if (!strcmp(renderer, "vulkan_zink"))
    {
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        load_vulkan();
        setenv("GALLIUM_DRIVER", "zink", 1);
        set_osm_bridge_tbl();
    }

    if (!strcmp(renderer, "gallium_freedreno"))
    {
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        load_vulkan();
        setenv("MESA_LOADER_DRIVER_OVERRIDE", "kgsl", 1);
        setenv("GALLIUM_DRIVER", "freedreno", 1);
        set_osm_bridge_tbl();
    }

    if (!strcmp(renderer, "gallium_panfrost"))
    {
        pojav_environ->config_renderer = RENDERER_VK_ZINK;
        setenv("GALLIUM_DRIVER", "panfrost", 1);
        setenv("MESA_DISK_CACHE_SINGLE_FILE", "1", 1);
        set_osm_bridge_tbl();
    }

    if (!strcmp(renderer, "gallium_virgl"))
    {
        pojav_environ->config_renderer = RENDERER_VIRGL;
        setenv("GALLIUM_DRIVER", "virpipe", 1);
        setenv("OSMESA_NO_FLUSH_FRONTBUFFER", "1", false);
        setenv("MESA_GL_VERSION_OVERRIDE", "4.3", 1);
        setenv("MESA_GLSL_VERSION_OVERRIDE", "430", 1);
        if (!strcmp(getenv("OSMESA_NO_FLUSH_FRONTBUFFER"), "1"))
            printf("VirGL: OSMesa buffer flush is DISABLED!\n");
        loadSymbolsVirGL();
        virglInit();
        return 0;
    }

    if (br_init()) br_setup_window();

    return 0;
}

// 获取当前线程的 JNIEnv（未附着则先 Attach，不 Detach，保留渲染线程的附着状态）
static JNIEnv *get_attached_env_for_renderer(JavaVM *jvm) {
    JNIEnv *jvm_env = NULL;
    jint env_result = (*jvm)->GetEnv(jvm, (void **) &jvm_env, JNI_VERSION_1_4);
    if (env_result == JNI_EDETACHED) {
        env_result = (*jvm)->AttachCurrentThread(jvm, &jvm_env, NULL);
    }
    if (env_result != JNI_OK) {
        printf("get_attached_env failed: %i\n", env_result);
        return NULL;
    }
    return jvm_env;
}

EXTERNAL_API int pojavInit() {
    pojav_environ->glfwThreadVmEnv = get_attached_env_for_renderer(pojav_environ->runtimeJavaVMPtr);
    if (pojav_environ->glfwThreadVmEnv == NULL) {
        printf("Failed to attach Java-side JNIEnv to GLFW thread\n");
        return 0;
    }
    // 桥初始化可能在其它线程上先行失败，此处于渲染线程兜底重试
    if (!ensureGlfwNativeBridgeInitialized(pojav_environ->glfwThreadVmEnv)) {
        printf("pojavInit: GLFW bridge is not initialized\n");
        return 0;
    }
    ANativeWindow_acquire(pojav_environ->pojavWindow);
    pojav_environ->savedWidth = ANativeWindow_getWidth(pojav_environ->pojavWindow);
    pojav_environ->savedHeight = ANativeWindow_getHeight(pojav_environ->pojavWindow);
    ANativeWindow_setBuffersGeometry(pojav_environ->pojavWindow,pojav_environ->savedWidth,pojav_environ->savedHeight,AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM);
    updateMonitorSize(pojav_environ->savedWidth, pojav_environ->savedHeight);
    pojavInitOpenGL();
    // 垂直同步开关关闭时主动切入异步模式，解除帧率对屏幕刷新率的锁定；开启时交由 MC 的交换间隔调用决定
    if (pojav_environ->config_renderer != RENDERER_VULKAN && !getenv("POJAV_VSYNC_IN_ZINK")) {
        setNativeWindowSwapInterval(pojav_environ->pojavWindow, 0);
    }
    return 1;
}

EXTERNAL_API void pojavSetWindowHint(int hint, int value) {
    if (hint != GLFW_CLIENT_API) return;
    switch (value) {
        case GLFW_NO_API:
            pojav_environ->config_renderer = RENDERER_VULKAN;
            /* Nothing to do: initialization is handled in Java-side */
            // pojavInitVulkan();
            break;
        case GLFW_OPENGL_API: {
            const char *renderer = getenv("POJAV_RENDERER");
            if (!strncmp("opengles", renderer, 8)) {
                pojav_environ->config_renderer = RENDERER_GL4ES;
            } else if (!strcmp(renderer, "vulkan_zink")) {
                pojav_environ->config_renderer = RENDERER_VK_ZINK;
            }
            /* Nothing to do: initialization is called in pojavCreateContext */
            // pojavInitOpenGL();
            break;
        }
        default:
            printf("GLFW: Unimplemented API 0x%x\n", value);
            abort();
    }
}

EXTERNAL_API void pojavSwapBuffers() {
    calculateFPS();

    if (pojav_environ->config_renderer == RENDERER_VK_ZINK
     || pojav_environ->config_renderer == RENDERER_GL4ES)
    {
        br_swap_buffers();
    }

    if (pojav_environ->config_renderer == RENDERER_VIRGL)
    {
        virglSwapBuffers();
    }

}

EXTERNAL_API void pojavMakeCurrent(void* window) {
    if (pojav_environ->config_renderer == RENDERER_VK_ZINK
     || pojav_environ->config_renderer == RENDERER_GL4ES)
    {
        br_make_current((basic_render_window_t*)window);
    }

    if (pojav_environ->config_renderer == RENDERER_VIRGL)
    {
        virglMakeCurrent(window);
    }

}

EXTERNAL_API void* pojavCreateContext(void* contextSrc) {
    if (pojav_environ->config_renderer == RENDERER_VULKAN)
        return (void *) pojav_environ->pojavWindow;

    if (pojav_environ->config_renderer == RENDERER_VIRGL)
        return virglCreateContext(contextSrc);

    return br_init_context((basic_render_window_t*)contextSrc);
}

void* maybe_load_vulkan() {
    // We use the env var because
    // 1. it's easier to do that
    // 2. it won't break if something will try to load vulkan and osmesa simultaneously
    if(getenv("VULKAN_PTR") == NULL) load_vulkan();
    return (void*) strtoul(getenv("VULKAN_PTR"), NULL, 0x10);
}

static int frameCount = 0;
static int fps = 0;
static time_t lastTime = 0;

// ---------------------------------------------------------------------------------------------
// ONA: per-frame ADPF hints (Android 13+, NDK APerformanceHint). The render thread tells the
// system after every frame how long it took against the screen's frame budget, so the CPU clocks
// rise inside the frame that falls behind (chunk loading, a GC, a busy server tick) instead of
// after a second of slow frames. This is what native games do; it only adds boost when needed
// and never lowers the governor's own choice. Loaded with dlsym: minSdk is 26.
//   ONA_FRAME_TARGET_NS: frame budget (FPS cap, or the refresh rate), ONA_ADPF=0: off
// ---------------------------------------------------------------------------------------------
typedef void* (*PFN_APerformanceHint_getManager)(void);
typedef void* (*PFN_APerformanceHint_createSession)(void*, const int32_t*, size_t, int64_t);
typedef int (*PFN_APerformanceHint_reportActualWorkDuration)(void*, int64_t);
static PFN_APerformanceHint_reportActualWorkDuration adpf_report;
static void* adpf_session;
static int adpf_state;          // 0 = not tried yet, 1 = running, -1 = not available
static int64_t adpf_last_ns;
static int64_t adpf_pending_ns; // very fast frames are reported in small groups
static int adpf_pending_frames;

static int64_t ona_now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void ona_adpf_start(int64_t now) {
    adpf_state = -1;
    const char* off = getenv("ONA_ADPF");
    if (off != NULL && strcmp(off, "0") == 0) return;
    void* android = dlopen("libandroid.so", RTLD_NOW);
    if (android == NULL) return;
    PFN_APerformanceHint_getManager get_manager =
            (PFN_APerformanceHint_getManager) dlsym(android, "APerformanceHint_getManager");
    PFN_APerformanceHint_createSession create_session =
            (PFN_APerformanceHint_createSession) dlsym(android, "APerformanceHint_createSession");
    adpf_report = (PFN_APerformanceHint_reportActualWorkDuration)
            dlsym(android, "APerformanceHint_reportActualWorkDuration");
    if (get_manager == NULL || create_session == NULL || adpf_report == NULL) return; // Android 12 and older
    void* manager = get_manager();
    if (manager == NULL) return;
    int64_t target = 16666666;
    const char* wanted = getenv("ONA_FRAME_TARGET_NS");
    if (wanted != NULL) {
        long long value = atoll(wanted);
        if (value >= 1000000LL && value <= 50000000LL) target = (int64_t) value; // 1 ms (unlimited FPS) .. 50 ms
    }
    int32_t tid = (int32_t) gettid();
    adpf_session = create_session(manager, &tid, 1, target);
    if (adpf_session == NULL) return;
    adpf_state = 1;
    adpf_last_ns = now;
    printf("ONA: ADPF per-frame hints on the render thread (%d), frame budget %lld us\n",
           tid, (long long) (target / 1000));
}

static void ona_adpf_frame(void) {
    if (adpf_state < 0) return;
    int64_t now = ona_now_ns();
    if (adpf_state == 0) {
        ona_adpf_start(now);
        return;
    }
    int64_t duration = now - adpf_last_ns;
    adpf_last_ns = now;
    if (duration <= 0 || duration > 1000000000LL) return; // paused, minimised
    adpf_pending_ns += duration;
    adpf_pending_frames++;
    // one report per frame, or per few frames above ~250 FPS (each report is a binder call)
    if (adpf_pending_ns < 4000000LL && adpf_pending_frames < 4) return;
    adpf_report(adpf_session, adpf_pending_ns / adpf_pending_frames);
    adpf_pending_ns = 0;
    adpf_pending_frames = 0;
}

void calculateFPS() {
    ona_adpf_frame();
    frameCount++;
    time_t currentTime = time(NULL);

    if (currentTime != lastTime) {
        lastTime = currentTime;
        fps = frameCount;
        frameCount = 0;
    }

    if (!pojav_environ->hasGraphicOutput && pojav_environ->dalvikJavaVMPtr && pojav_environ->bridgeClazz && pojav_environ->method_onGraphicOutput) {
        pojav_environ->hasGraphicOutput = true;

        JNIEnv *dalvikEnv = NULL;
        jboolean detachedBefore = (*pojav_environ->dalvikJavaVMPtr)->GetEnv(pojav_environ->dalvikJavaVMPtr, (void **) &dalvikEnv, JNI_VERSION_1_4) == JNI_EDETACHED;
        if (detachedBefore) {
            (*pojav_environ->dalvikJavaVMPtr)->AttachCurrentThread(pojav_environ->dalvikJavaVMPtr, &dalvikEnv, NULL);
        }
        if (dalvikEnv != NULL) {
            (*dalvikEnv)->CallStaticVoidMethod(dalvikEnv, pojav_environ->bridgeClazz, pojav_environ->method_onGraphicOutput);
            if (detachedBefore) {
                (*pojav_environ->dalvikJavaVMPtr)->DetachCurrentThread(pojav_environ->dalvikJavaVMPtr);
            }
        }
    }
}

EXTERNAL_API JNIEXPORT void JNICALL
Java_org_lwjgl_vulkan_VK_updateFps(ABI_COMPAT JNIEnv *env, ABI_COMPAT jclass thiz) {
    calculateFPS();
}

EXTERNAL_API JNIEXPORT jint JNICALL
Java_org_lwjgl_glfw_CallbackBridge_getCurrentFps(JNIEnv *env, jclass clazz) {
    return fps;
}

EXTERNAL_API JNIEXPORT jlong JNICALL
Java_org_lwjgl_vulkan_VK_getVulkanDriverHandle(ABI_COMPAT JNIEnv *env, ABI_COMPAT jclass thiz) {
    printf("EGLBridge: LWJGL-side Vulkan loader requested the Vulkan handle\n");
    return (jlong) maybe_load_vulkan();
}

EXTERNAL_API void pojavSwapInterval(int interval) {
    lastSwapInterval = interval;

    if (pojav_environ->config_renderer == RENDERER_VK_ZINK
     || pojav_environ->config_renderer == RENDERER_GL4ES)
    {
        br_swap_interval(interval);
    }

    if (pojav_environ->config_renderer == RENDERER_VIRGL)
    {
        virglSwapInterval(interval);
    }

}

