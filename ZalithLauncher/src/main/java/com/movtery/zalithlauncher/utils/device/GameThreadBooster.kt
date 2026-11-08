/*
 * ONA Launcher 2 (based on Zalith Launcher 2)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.utils.device

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import com.movtery.zalithlauncher.utils.logging.Logger
import org.lwjgl.glfw.CallbackBridge
import java.io.File

/**
 * ONA: more FPS without frame generation, from the CPU side.
 *  - The game's own threads get scheduler priority by role: the render thread like a display
 *    thread, the integrated server just below, chunk builders / workers a little above normal.
 *    Android otherwise treats them like any background Java thread and the governor parks
 *    them on slow cores and low clocks.
 *  - On Android 12+ an ADPF performance hint session tells the system how long a frame may
 *    take (one screen refresh) and how long frames really take, so it raises the clocks of
 *    exactly those threads when the game falls behind (what native games do).
 * Threads are found by the names the JVM gives them in /proc/self/task, new ones (worlds,
 * servers) are picked up every few seconds. Setting "onaBoostGameThreads" (default on).
 */
class GameThreadBooster(private val context: Context, refreshHz: Float) {
    private val targetNs = (1_000_000_000.0 / refreshHz.coerceIn(30f, 165f)).toLong()
    private val boosted = HashMap<Int, Int>()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var session: Any? = null
    private var sessionTids: Set<Int> = emptySet()

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "ONA-ThreadBooster").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        closeSession()
    }

    private fun loop() {
        var tick = 0
        while (running) {
            try {
                // Threads appear while the game loads and when a world opens: rescan every 3 s
                if (tick % 3 == 0) scanThreads()
                reportFrameTime()
                tick++
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return
            } catch (e: Throwable) {
                Logger.warning(TAG, "Thread booster step failed", e)
            }
        }
    }

    private fun priorityFor(name: String): Int? = when {
        name == "Render thread" || name == "Client thread" -> Process.THREAD_PRIORITY_URGENT_DISPLAY
        name == "Server thread" -> Process.THREAD_PRIORITY_DISPLAY
        name.startsWith("Chunk") || name.startsWith("Worker-Main") || name.startsWith("C2ME") ||
            name.startsWith("Sodium") || name.startsWith("Embeddium") || name.startsWith("Voxy") ||
            name.startsWith("Dedicated Voxy") -> Process.THREAD_PRIORITY_MORE_FAVORABLE * 2
        else -> null
    }

    private fun scanThreads() {
        val tasks = File("/proc/self/task").listFiles() ?: return
        val alive = HashSet<Int>()
        val hot = HashSet<Int>()
        for (task in tasks) {
            val tid = task.name.toIntOrNull() ?: continue
            alive.add(tid)
            val name = runCatching { File(task, "comm").readText().trim() }.getOrNull() ?: continue
            val priority = priorityFor(name) ?: continue
            if (name == "Render thread" || name == "Client thread" || name == "Server thread") hot.add(tid)
            if (boosted[tid] == priority) continue
            runCatching {
                Process.setThreadPriority(tid, priority)
                boosted[tid] = priority
                Logger.info(TAG, "\"$name\" ($tid) priority $priority")
            }.onFailure { boosted[tid] = priority } // not allowed here: do not retry every scan
        }
        boosted.keys.retainAll(alive)
        if (hot.isNotEmpty() && hot != sessionTids) openSession(hot)
    }

    private fun openSession(tids: Set<Int>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // Android 13+: the render thread reports every frame itself (egl_bridge.c, NDK hints)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        closeSession()
        runCatching {
            val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return
            val created = manager.createHintSession(tids.toIntArray(), targetNs) ?: return
            session = created
            sessionTids = tids
            Logger.info(TAG, "ADPF hint session for ${tids.size} game threads, target ${targetNs / 1000} us per frame")
        }.onFailure { Logger.warning(TAG, "No ADPF hint session", it) }
    }

    private fun reportFrameTime() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val hint = session as? PerformanceHintManager.Session ?: return
        val fps = runCatching { CallbackBridge.getCurrentFps() }.getOrDefault(0)
        if (fps <= 0) return
        runCatching { hint.reportActualWorkDuration(1_000_000_000L / fps) }
    }

    private fun closeSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { (session as? PerformanceHintManager.Session)?.close() }
        }
        session = null
        sessionTids = emptySet()
    }

    companion object {
        private const val TAG = "GameThreadBooster"
    }
}
