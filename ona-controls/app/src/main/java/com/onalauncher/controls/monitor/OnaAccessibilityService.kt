/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.monitor

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Two jobs, both cheap:
 *  1. Foreground detection: the system pushes TYPE_WINDOW_STATE_CHANGED only when a window
 *     changes (no polling, no wake-ups). We forward real activity switches to [ForegroundAppMonitor].
 *  2. Touch injection for the ACCESSIBILITY_TOUCH backend (dispatchGesture, see GestureInjector).
 *
 * Config: res/xml/ona_overlay_accessibility.xml (event type filter, canPerformGestures).
 */
class OnaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: OnaAccessibilityService? = null
            private set
    }

    /** className -> is it an Activity? (popups, toasts and IME windows must not hide the overlay) */
    private val activityCache = HashMap<String, Boolean>()
    private var imePackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        imePackage = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let { ComponentName.unflattenFromString(it)?.packageName }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (isIgnored(pkg)) return
        val cls = event.className?.toString()
        if (cls == null) { if (pkg == packageName) return } else if (!isActivity(pkg, cls)) return
        ForegroundAppMonitor.report(pkg, ForegroundAppMonitor.Source.ACCESSIBILITY)
    }

    private fun isIgnored(pkg: String): Boolean =
        pkg == "com.android.systemui" || // notification shade, recents animation
            pkg == "android" ||
            pkg == imePackage // keyboard popping up over the game

    private fun isActivity(pkg: String, cls: String): Boolean {
        val key = "$pkg/$cls"
        activityCache[key]?.let { return it }
        val result = try {
            packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            // Package not visible to us or a plain window: framework widgets are never activities.
            // Our own overlay windows / dialogs are never activities either (only real ONA screens count).
            pkg != packageName && !cls.startsWith("android.")
        }
        if (activityCache.size > 512) activityCache.clear()
        activityCache[key] = result
        return result
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
}
