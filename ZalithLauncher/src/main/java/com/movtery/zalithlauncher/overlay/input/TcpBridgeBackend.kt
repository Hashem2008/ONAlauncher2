/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.movtery.zalithlauncher.overlay.input

import android.util.Log
import com.movtery.zalithlauncher.overlay.model.GamepadAxis
import com.movtery.zalithlauncher.overlay.model.OverlayKey
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Text protocol over TCP to a PC / Wine side (reference server: tools/ona-overlay-bridge/ona_bridge.py).
 * One line per event, UTF-8, '\n' terminated:
 *
 *   HELLO ONA 1                     sent once after connecting
 *   K <KEY> <1|0>                   keyboard, KEY = OverlayKey enum name (SPACE, W, L_SHIFT, F1 ...)
 *   M <MLB|MRB|MMB> <1|0>           mouse button
 *   W <steps>                       wheel, +up / -down
 *   R <dx> <dy>                     relative mouse move in pixels (coalesced, at most ~120/s)
 *   G <GP_*> <1|0>                  gamepad button
 *   A <LX|LY|RX|RY|LT|RT> <value>   gamepad axis, -1..1 (triggers 0..1)
 *
 * One writer thread; the UI thread only enqueues. Mouse moves are summed between writes so a fast
 * trackpad never floods the socket. While disconnected, events are dropped (no stale replay) and
 * the thread retries every 2 s. TCP_NODELAY keeps latency at one RTT.
 */
class TcpBridgeBackend(private val host: String, private val port: Int) : InputBackend {
    override val id = "tcp"

    private val queue = LinkedBlockingQueue<String>(1024)
    @Volatile private var running = false
    @Volatile private var connected = false
    private var thread: Thread? = null

    private val moveLock = Any()
    private var accDx = 0f
    private var accDy = 0f

    override fun supports(category: InputBackend.Capability) = category != InputBackend.Capability.TOUCH

    override fun status(): String = if (connected) "PC $host:$port ✓" else "PC $host:$port …"

    override fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "ona-overlay-tcp").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun send(line: String) {
        if (running && connected) queue.offer(line)
    }

    override fun key(key: OverlayKey, down: Boolean) = send("K ${key.name} ${if (down) 1 else 0}")
    override fun mouseButton(key: OverlayKey, down: Boolean) = when (key) {
        OverlayKey.WHEEL_UP -> if (down) mouseWheel(1) else Unit
        OverlayKey.WHEEL_DOWN -> if (down) mouseWheel(-1) else Unit
        else -> send("M ${key.name} ${if (down) 1 else 0}")
    }

    override fun mouseWheel(steps: Int) = send("W $steps")
    override fun gamepadButton(key: OverlayKey, down: Boolean) = send("G ${key.name} ${if (down) 1 else 0}")
    override fun gamepadAxis(axis: GamepadAxis, value: Float) =
        send(String.format(Locale.ROOT, "A %s %.3f", axis.name, value))

    override fun mouseMove(dx: Float, dy: Float) {
        if (!connected) return
        synchronized(moveLock) { accDx += dx; accDy += dy }
    }

    private fun loop() {
        while (running) {
            var socket: Socket? = null
            try {
                socket = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    connect(InetSocketAddress(host, port), 1500)
                }
                val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                out.write("HELLO ONA 1\n"); out.flush()
                queue.clear()
                connected = true
                Log.i("OnaOverlay", "Bridge connected $host:$port")
                while (running) {
                    val first = queue.poll(8, TimeUnit.MILLISECONDS)
                    var wrote = false
                    if (first != null) {
                        out.write(first); out.write('\n'.code)
                        while (true) {
                            val next = queue.poll() ?: break
                            out.write(next); out.write('\n'.code)
                        }
                        wrote = true
                    }
                    val (dx, dy) = synchronized(moveLock) {
                        val ix = accDx.toInt()
                        val iy = accDy.toInt()
                        accDx -= ix; accDy -= iy // keep the sub-pixel rest for the next write
                        ix to iy
                    }
                    if (dx != 0 || dy != 0) {
                        out.write("R $dx $dy\n"); wrote = true
                    }
                    if (wrote) out.flush()
                }
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                if (connected) Log.w("OnaOverlay", "Bridge lost: ${t.message}")
            } finally {
                connected = false
                runCatching { socket?.close() }
            }
            try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
        }
    }
}
