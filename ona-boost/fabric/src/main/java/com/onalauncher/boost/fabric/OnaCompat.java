/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost.fabric;

import com.onalauncher.boost.Phone;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the phone's OpenGL layer really offers, for the compatibility fixes */
public final class OnaCompat {
    /**
     * Options of shader packs (Complementary Reimagined / Unbound and the packs built on them) whose
     * parts only run with compute shaders and storage buffers, and the value that switches them off
     */
    private static final Map<String, String> NEEDS_COMPUTE = new LinkedHashMap<>();

    static {
        NEEDS_COMPUTE.put("COLORED_LIGHTING", "0");
        NEEDS_COMPUTE.put("WORLD_SPACE_REFLECTIONS", "-1");
        NEEDS_COMPUTE.put("WORLD_SPACE_PLAYER_REF", "-1");
    }

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

    /**
     * The option values Iris is about to load a shader pack with: without compute, the options
     * that need it are switched off (a profile picked in the game switches them on again, and the
     * pack then fails to load). Options a pack does not have are ignored by Iris.
     */
    public static Map<String, String> packOptions(Map<String, String> changed) {
        try {
            if (!noCompute()) return changed;
            Map<String, String> safe = new HashMap<>();
            if (changed != null) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) changed).entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) safe.put(entry.getKey().toString(), entry.getValue().toString());
                }
            }
            List<String> switchedOff = new ArrayList<>();
            for (Map.Entry<String, String> option : NEEDS_COMPUTE.entrySet()) {
                String now = safe.put(option.getKey(), option.getValue());
                if (now != null && !now.trim().equals(option.getValue())) switchedOff.add(option.getKey());
            }
            if (!switchedOff.isEmpty()) {
                LoggerFactory.getLogger("ONA Boost").info("shader pack: {} kept off (needs compute shaders, this phone's OpenGL layer runs without)", String.join(", ", switchedOff));
            }
            return safe;
        } catch (Throwable t) {
            return changed;
        }
    }
}
