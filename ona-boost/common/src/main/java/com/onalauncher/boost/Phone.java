/*
 * ONA Boost - adaptive performance for Minecraft on phones (ONA Launcher 2)
 * GPL-3.0-or-later
 */
package com.onalauncher.boost;

/** ONA Boost is made for phones only */
public final class Phone {
    private Phone() {
    }

    /** Android launchers report the OS version as "Android-N" and set their own environment */
    public static boolean isPhone() {
        String os = System.getProperty("os.version", "");
        if (os.startsWith("Android")) return true;
        return System.getenv("ZALITH_VERSION_CODE") != null || System.getenv("POJAV_RENDERER") != null
            || System.getenv("ANDROID_ROOT") != null;
    }
}
