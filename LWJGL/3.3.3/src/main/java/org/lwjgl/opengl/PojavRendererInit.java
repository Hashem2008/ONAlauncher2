package org.lwjgl.opengl;

import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.SharedLibrary;

import javax.annotation.Nullable;

/**
 * Class for initializing renderer-specific callbacks. Allows to reliably initialize
 * any callbacks needed for renderers by using the same FunctionProvider as used for loading
 * GL symbols.
 * */
public class PojavRendererInit {

    public static void onCreateCapabilities(FunctionProvider functionProvider) {
        String rendererName = null;
        if(functionProvider instanceof SharedLibrary) {
            SharedLibrary rendererLibrary = (SharedLibrary) functionProvider;
            rendererName = rendererLibrary.getName();
        }
        if(!isValidString(rendererName)) {
            rendererName = System.getProperty("org.lwjgl.opengl.libname");
        }
        if(!isValidString(rendererName)) {
            System.out.println("PojavRendererInit: Failed to find Pojav renderer name! " +
                    "Renderer-specific initialization may not work properly");
        }
        // NOTE: hardcoded gl4es libname
        if (rendererName.endsWith("libgl4es_114.so") || rendererName.endsWith("libng_gl4es.so")) {
            nativeInitGl4esInternals(functionProvider);
        }
    }

    /** GL functions ona_glfix.c wraps or needs */
    private static final java.util.Set<String> ONA_WRAPPED = new java.util.HashSet<>(java.util.Arrays.asList(
            "glTexImage2D", "glTexStorage2D", "glRenderbufferStorage", "glRenderbufferStorageMultisample",
            "glCheckFramebufferStatus", "glGetIntegerv", "glGetFramebufferAttachmentParameteriv"
    ));

    /**
     * ONA: on OpenGL ES based renderers (ONA_GL_FORMAT_FIX=1), shader pack render targets in formats
     * ES cannot draw into are allocated in the nearest format it can, so the framebuffer is complete.
     */
    public static FunctionProvider wrapProvider(final FunctionProvider provider) {
        if (!"1".equals(System.getenv("ONA_GL_FORMAT_FIX"))) return provider;
        try {
            nativeWrapGlFunction("glFinish", 0L); // fails here, not mid-frame, when the native side is missing
        } catch (Throwable t) {
            System.out.println("PojavRendererInit: GL format fix unavailable: " + t);
            return provider;
        }
        System.out.println("PojavRendererInit: GL format fix on");
        return new FunctionProvider() {
            @Override
            public long getFunctionAddress(java.nio.ByteBuffer functionName) {
                long real = provider.getFunctionAddress(functionName);
                if (real == 0L) return real;
                String name = org.lwjgl.system.MemoryUtil.memASCII(org.lwjgl.system.MemoryUtil.memAddress(functionName));
                if (!ONA_WRAPPED.contains(name)) return real;
                long wrapper = nativeWrapGlFunction(name, real);
                return wrapper != 0L ? wrapper : real;
            }
        };
    }

    private static native long nativeWrapGlFunction(String name, long real);

    private static boolean isValidString(@Nullable  String s) {
        return s != null && !s.isEmpty();
    }

    public static native void nativeInitGl4esInternals(FunctionProvider functionProvider);
}