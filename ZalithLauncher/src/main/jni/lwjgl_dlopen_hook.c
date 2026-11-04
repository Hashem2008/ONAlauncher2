//
// Created by maks on 06.01.2025.
//

#include <android/api-level.h>
#include <android/log.h>
#include <jni.h>

#include <environ/environ.h>

#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>

extern void* maybe_load_vulkan();

/**
 * ONA: glibc library names that bionic provides under another name. Returns NULL when the name
 * is not one of them.
 */
static const char* android_library_name(const char* filename) {
    static const char* const NAMES[][2] = {
            {"libc.so.6", "libc.so"},
            {"libm.so.6", "libm.so"},
            {"libdl.so.2", "libdl.so"},
            {"libpthread.so.0", "libc.so"},
            {"librt.so.1", "libc.so"},
    };
    const char* base = strrchr(filename, '/');
    base = base != NULL ? base + 1 : filename;
    for (size_t i = 0; i < sizeof(NAMES) / sizeof(NAMES[0]); i++) {
        if (strcmp(base, NAMES[i][0]) == 0) return NAMES[i][1];
    }
    return NULL;
}

/**
 * Basically a verbatim implementation of ndlopen(), found at
 * https://github.com/PojavLauncherTeam/lwjgl3/blob/3.3.1/modules/lwjgl/core/src/generated/c/linux/org_lwjgl_system_linux_DynamicLinkLoader.c#L11
 * but with our own additions for stuff like vulkanmod.
 */
static jlong ndlopen_bugfix(__attribute__((unused)) JNIEnv *env,
                            __attribute__((unused)) jclass class,
                            jlong filename_ptr,
                            jint jmode) {
    const char* filename = (const char*) filename_ptr;

    // Oveeride vulkan loading to let us load vulkan ourselves
    if(strstr(filename, "libvulkan.so") == filename) {
        printf("LWJGL linkerhook: replacing load for libvulkan.so with custom driver\n");
        return (jlong) maybe_load_vulkan();
    }

    // This hook also serves the task of mitigating a bug: the idea is that since, on Android 10 and
    // earlier, the linker doesn't really do namespace nesting.
    // It is not a problem as most of the libraries are in the launcher path, but when you try to run
    // VulkanMod which loads shaderc outside of the default jni libs directory through this method,
    // it can't load it because the path is not in the allowed paths for the anonymous namesapce.
    // This method fixes the issue by being in libpojavexec, and thus being in the classloader namespace

    int mode = (int)jmode;
    void* handle = dlopen(filename, mode);
    if (handle == NULL) {
        // ONA: mods written for desktop Linux ask for glibc's versioned names (Voxy's ThreadUtils
        // loads "libc.so.6"); Android's bionic has the same functions under the plain names.
        const char* android_name = android_library_name(filename);
        if (android_name != NULL) {
            __android_log_print(ANDROID_LOG_INFO, "LwjglLinkerHook", "%s -> %s", filename, android_name);
            handle = dlopen(android_name, mode);
        }
    }
    return (jlong) handle;
}

/** dlerror() for LWJGL, which Pojav's liblwjgl does not export (LWJGL calls it after a failed load) */
static jlong ndlerror_impl(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jclass class) {
    return (jlong) dlerror();
}

/**
 * Install the LWJGL dlopen hook. This allows us to mitigate linker bugs and add custom library overrides.
 */
void installLwjglDlopenHook() {
    __android_log_print(ANDROID_LOG_INFO, "LwjglLinkerHook", "Installing LWJGL dlopen() hook");
    JNIEnv* env = pojav_environ->runtimeJNIEnvPtr_JRE;
    jclass dynamicLinkLoader = (*env)->FindClass(env, "org/lwjgl/system/linux/DynamicLinkLoader");
    if(dynamicLinkLoader == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "LwjglLinkerHook", "Failed to find the target class");
        (*env)->ExceptionClear(env);
        return;
    }
    JNINativeMethod ndlopenMethod[] = {
            {"ndlopen", "(JI)J", &ndlopen_bugfix}
    };
    if((*env)->RegisterNatives(env, dynamicLinkLoader, ndlopenMethod, 1) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "LwjglLinkerHook", "Failed to register the hooked method");
        (*env)->ExceptionClear(env);
    }
    // ONA: without it a failed load ends in "undefined symbol: ...DynamicLinkLoader_ndlerror"
    JNINativeMethod ndlerrorMethod[] = {
            {"ndlerror", "()J", &ndlerror_impl}
    };
    if((*env)->RegisterNatives(env, dynamicLinkLoader, ndlerrorMethod, 1) != 0) {
        __android_log_print(ANDROID_LOG_WARN, "LwjglLinkerHook", "Could not register ndlerror");
        (*env)->ExceptionClear(env);
    }
}