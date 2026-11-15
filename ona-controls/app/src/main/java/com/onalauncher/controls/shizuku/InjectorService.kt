/*
 * ONA Controls - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.shizuku

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import com.onalauncher.controls.IInjector
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Lives in a separate process that Shizuku starts with SHELL rights (like `adb shell`), so it may
 * call InputManager.injectInputEvent: real key events into ANY app, the same way scrcpy does.
 * Nothing here runs in the normal app process.
 */
class InjectorService : IInjector.Stub() {

    private var target: Any? = null
    private var inject: Method? = null
    private var found = "none"

    init {
        try {
            // Android 14+: the static instance moved to InputManagerGlobal
            val g = Class.forName("android.hardware.input.InputManagerGlobal")
            target = g.getMethod("getInstance").invoke(null)
            inject = g.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            found = "InputManagerGlobal"
        } catch (_: Throwable) {
            try {
                val c = Class.forName("android.hardware.input.InputManager")
                target = c.getMethod("getInstance").invoke(null)
                inject = c.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                found = "InputManager"
            } catch (t: Throwable) {
                found = "error: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
    }

    private val setActionButton: Method? = runCatching {
        MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
    }.getOrNull()
    /** Mouse buttons held right now (MotionEvent.BUTTON_* bits) and when the first went down. */
    private var buttons = 0
    private var mouseDownTime = 0L

    private fun send(ev: InputEvent): Boolean = try {
        (inject?.invoke(target, ev, 0) as? Boolean) ?: false
    } catch (_: Throwable) {
        false
    }

    private fun motion(action: Int, x: Float, y: Float, now: Long, actionButton: Int = 0, wheel: Float = 0f): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            pressure = if (buttons != 0) 1f else 0f
            size = 1f
            if (wheel != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, wheel)
        })
        val downTime = if (buttons != 0 || action == MotionEvent.ACTION_UP) mouseDownTime else now
        val ev = MotionEvent.obtain(
            downTime, now, action, 1, props, coords, 0, buttons, 1f, 1f,
            0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        if (actionButton != 0) runCatching { setActionButton?.invoke(ev, actionButton) }
        return ev
    }

    private fun sendRecycle(ev: MotionEvent): Boolean = send(ev).also { ev.recycle() }

    override fun mouse(kind: Int, x: Float, y: Float, button: Int, value: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        return when (kind) {
            0 -> sendRecycle(motion(if (buttons != 0) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE, x, y, now))
            1 -> {
                if ((buttons and button) != 0) return true // already down
                var ok = true
                if (buttons == 0) {
                    mouseDownTime = now
                    buttons = button
                    ok = sendRecycle(motion(MotionEvent.ACTION_DOWN, x, y, now))
                } else {
                    buttons = buttons or button
                }
                sendRecycle(motion(MotionEvent.ACTION_BUTTON_PRESS, x, y, now, button)) && ok
            }
            2 -> {
                if ((buttons and button) == 0) return true // not down
                buttons = buttons and button.inv()
                var ok = sendRecycle(motion(MotionEvent.ACTION_BUTTON_RELEASE, x, y, now, button))
                if (buttons == 0) ok = sendRecycle(motion(MotionEvent.ACTION_UP, x, y, now)) && ok
                ok
            }
            3 -> sendRecycle(motion(MotionEvent.ACTION_SCROLL, x, y, now, wheel = value))
            else -> false
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun exit() {
        exitProcess(0)
    }
    override fun probe(): String = found

    override fun key(code: Int, down: Boolean, meta: Int, downTime: Long): Boolean {
        val m = inject ?: return false
        val now = SystemClock.uptimeMillis()
        val ev = KeyEvent(
            downTime, now,
            if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            InputDevice.SOURCE_KEYBOARD
        )
        return try {
            m.invoke(target, ev, 0) as? Boolean ?: false // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (_: Throwable) {
            false
        }
    }
}
