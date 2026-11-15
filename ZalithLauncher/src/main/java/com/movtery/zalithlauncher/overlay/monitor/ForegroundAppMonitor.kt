/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.movtery.zalithlauncher.overlay.monitor

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for "which app is on screen now".
 *
 * Producers (whichever is available, best first):
 *  1. [OnaAccessibilityService] - event driven (TYPE_WINDOW_STATE_CHANGED), zero polling.
 *  2. [UsageStatsForegroundPoller] - only when accessibility is off; polls UsageEvents once per
 *     second and ONLY while the screen is on and the overlay service runs.
 * Consumer: OverlayControllerService collects [foreground] and shows / hides the controls.
 */
object ForegroundAppMonitor {
    enum class Source { ACCESSIBILITY, USAGE_STATS }

    private val _foreground = MutableStateFlow<String?>(null)
    val foreground: StateFlow<String?> get() = _foreground

    @Volatile var lastSource: Source? = null
        private set

    fun report(pkg: String, source: Source) {
        lastSource = source
        if (_foreground.value != pkg) _foreground.value = pkg
    }

    fun isAccessibilityEnabled(ctx: Context): Boolean {
        val me = ComponentName(ctx, OnaAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        for (s in splitter) {
            val cn = ComponentName.unflattenFromString(s) ?: continue
            if (cn == me) return true
        }
        return false
    }

    /** Accessibility service is enabled AND currently connected. */
    fun isAccessibilityRunning(): Boolean = OnaAccessibilityService.instance != null

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
