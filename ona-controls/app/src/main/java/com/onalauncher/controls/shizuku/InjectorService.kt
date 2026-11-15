/*
 * ONA Controls - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.shizuku

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
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
