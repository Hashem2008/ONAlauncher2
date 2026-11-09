/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost.fabric;

import com.onalauncher.boost.Phone;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.LoggerFactory;

/** What the phone's OpenGL layer really offers, for the compatibility fixes */
public final class OnaCompat {
    private static Boolean noCompute;

    private OnaCompat() {
    }

    /**
     * True on a phone whose OpenGL layer did not turn compute shaders on. Iris checks compute and
     * images by function address, and an OpenGL ES layer has those addresses anyway, so a pack
     * would take its compute / image path and fail to convert to OpenGL ES.
     */
    public static boolean noCompute() {
        if (noCompute != null) return noCompute;
        if (!Phone.isPhone()) {
            noCompute = false;
            return false;
        }
        try {
            GLCapabilities caps = GL.getCapabilities();
            noCompute = !(caps.OpenGL43 || caps.GL_ARB_compute_shader);
        } catch (Throwable notYet) {
            // no OpenGL context on this thread yet: decided on a later call
            return false;
        }
        if (noCompute) {
            LoggerFactory.getLogger("ONA Boost").info("compute shaders and images off for shader packs (the OpenGL layer runs without compute)");
        }
        return noCompute;
    }
}
