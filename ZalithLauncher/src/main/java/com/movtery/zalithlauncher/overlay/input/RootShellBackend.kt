/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.movtery.zalithlauncher.overlay.input

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.movtery.zalithlauncher.overlay.model.OverlayKey
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.hypot

/**
 * Rooted phones: one long-lived `su` shell (started once, not per event) fed with
 * `input keyevent` / `input tap` / `input swipe`. Works into any app, including Winlator.
 * Limits of the stock `input` tool: a key is a full press (down+up on our DOWN, nothing on UP),
 * and a held finger becomes a swipe when it is lifted. Mouse moves are not possible here.
 */
class RootShellBackend : InputBackend {
    override val id = "root"

    private val queue = LinkedBlockingQueue<String>(256)
    @Volatile private var ok = false
    @Volatile private var failed = false
    private var process: Process? = null
    private var thread: Thread? = null

    private class Down(val x: Float, val y: Float, val time: Long)
    private val downs = HashMap<Int, Down>()

    override fun supports(category: InputBackend.Capability) =
        category == InputBackend.Capability.KEYBOARD || category == InputBackend.Capability.TOUCH ||
            category == InputBackend.Capability.GAMEPAD

    override fun status(): String = when {
        ok -> "Root ✓"
        failed -> "Root: su refused"
        else -> "Root …"
    }

    override fun start() {
        if (thread != null) return
        thread = Thread({
            try {
                val p = ProcessBuilder("su").redirectErrorStream(true).start()
                process = p
                val out = BufferedWriter(OutputStreamWriter(p.outputStream))
                ok = true
                while (true) {
                    val line = queue.take()
                    out.write(line); out.write("\n"); out.flush()
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                Log.w("OnaOverlay", "su failed: ${t.message}")
                failed = true
            } finally {
                ok = false
                runCatching { process?.destroy() }
            }
        }, "ona-overlay-su").apply { isDaemon = true; start() }
    }

    override fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun run(cmd: String) {
        if (!failed) queue.offer(cmd)
    }

    override fun key(key: OverlayKey, down: Boolean) {
        if (down && key.androidCode != KeyEvent.KEYCODE_UNKNOWN) run("input keyevent ${key.androidCode}")
    }

    override fun gamepadButton(key: OverlayKey, down: Boolean) = this.key(key, down)

    override fun touch(pointer: Int, x: Float, y: Float, action: TouchAction) {
        when (action) {
            TouchAction.DOWN -> downs[pointer] = Down(x, y, SystemClock.uptimeMillis())
            TouchAction.MOVE -> Unit
            TouchAction.UP -> {
                val d = downs.remove(pointer) ?: return
                val ms = (SystemClock.uptimeMillis() - d.time).coerceIn(1L, 3000L)
                if (hypot(x - d.x, y - d.y) < 12f && ms < 400L) {
                    run("input tap ${d.x.toInt()} ${d.y.toInt()}")
                } else {
                    run("input swipe ${d.x.toInt()} ${d.y.toInt()} ${x.toInt()} ${y.toInt()} $ms")
                }
            }
        }
    }
}
