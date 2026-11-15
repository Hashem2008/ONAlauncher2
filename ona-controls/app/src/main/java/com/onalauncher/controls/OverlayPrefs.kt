/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Where key / mouse / gamepad events go. */
enum class BackendType {
    /** TCP to the ONA bridge on a PC (pc-bridge/ona_bridge.py): keys, mouse, gamepad. */
    PC_BRIDGE,
    /** Accessibility gestures: touch taps / drags on the app under the overlay (no root). */
    ACCESSIBILITY_TOUCH,
    /** Root shell (`input keyevent`, `input tap`): keys + taps into any app, needs su. */
    ROOT_SHELL
}

object OverlayPrefs {
    private const val FILE = "ona_overlay"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun backend(ctx: Context): BackendType =
        runCatching { BackendType.valueOf(sp(ctx).getString("backend", null)!!) }.getOrDefault(BackendType.ACCESSIBILITY_TOUCH)

    fun setBackend(ctx: Context, b: BackendType) = sp(ctx).edit { putString("backend", b.name) }

    fun host(ctx: Context): String = sp(ctx).getString("host", "192.168.1.10") ?: "192.168.1.10"
    fun setHost(ctx: Context, v: String) = sp(ctx).edit { putString("host", v.trim()) }

    fun port(ctx: Context): Int = sp(ctx).getInt("port", 7788)
    fun setPort(ctx: Context, v: Int) = sp(ctx).edit { putInt("port", v.coerceIn(1, 65535)) }

    /** User wants the service running (restarted from the settings screen / after reboot of the app). */
    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit { putBoolean("enabled", v) }

    /** Vibrate on button press. */
    fun haptics(ctx: Context): Boolean = sp(ctx).getBoolean("haptics", true)
    fun setHaptics(ctx: Context, v: Boolean) = sp(ctx).edit { putBoolean("haptics", v) }

    /** Floating bubble position (pixels). */
    fun bubbleX(ctx: Context): Int = sp(ctx).getInt("bubbleX", 0)
    fun bubbleY(ctx: Context): Int = sp(ctx).getInt("bubbleY", 200)
    fun setBubble(ctx: Context, x: Int, y: Int) = sp(ctx).edit { putInt("bubbleX", x); putInt("bubbleY", y) }

    /** Hide/show (eye) button position in pixels; -1 = not moved yet (sits under the bubble). */
    fun eyeX(ctx: Context): Int = sp(ctx).getInt("eyeX", -1)
    fun eyeY(ctx: Context): Int = sp(ctx).getInt("eyeY", -1)
    fun setEye(ctx: Context, x: Int, y: Int) = sp(ctx).edit { putInt("eyeX", x); putInt("eyeY", y) }

}
