/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.monitor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fallback foreground detector when the accessibility service is off.
 *
 * Battery rules:
 *  - polls only while the screen is interactive (stops on SCREEN_OFF, resumes on SCREEN_ON),
 *  - reads only the events since the last poll (queryEvents(last, now)), never full stats,
 *  - steps aside as soon as the accessibility service connects (event driven is better).
 */
class UsageStatsForegroundPoller(
    private val context: Context,
    private val scope: CoroutineScope,
    private val intervalMs: Long = 1000L
) {
    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private var job: Job? = null
    private var lastQuery = System.currentTimeMillis() - 60_000L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> pause()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> resume()
            }
        }
    }
    private var registered = false

    fun start() {
        if (!registered) {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(context, screenReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) resume()
    }

    fun stop() {
        pause()
        if (registered) {
            runCatching { context.unregisterReceiver(screenReceiver) }
            registered = false
        }
    }

    private fun pause() {
        job?.cancel()
        job = null
    }

    private fun resume() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                if (!ForegroundAppMonitor.isAccessibilityRunning() && ForegroundAppMonitor.hasUsageAccess(context)) {
                    pollOnce()
                }
                delay(intervalMs)
            }
        }
    }

    private fun pollOnce() {
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(lastQuery, now) }.getOrNull() ?: return
        lastQuery = now
        var latest: String? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latest = e.packageName
        }
        val pkg = latest ?: return
        ForegroundAppMonitor.report(pkg, ForegroundAppMonitor.Source.USAGE_STATS)
    }
}
