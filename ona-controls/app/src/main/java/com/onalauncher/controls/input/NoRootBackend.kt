/*
 * ONA Controls - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.input

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.onalauncher.controls.model.OverlayKey
import com.onalauncher.controls.monitor.OnaAccessibilityService

/**
 * No root, no PC:
 *  - KEYS, best: Shizuku (shell rights, InputManager.injectInputEvent) reaches ANY app, see
 *    shizuku/ShizukuKeys.kt. Used whenever Shizuku is running and allowed.
 *  - KEYS, fallback: Android 13+ lets an accessibility service act as an input method
 *    (FLAG_INPUT_METHOD_EDITOR, see res/xml/ona_overlay_accessibility.xml). Its input connection
 *    belongs to the app in front, so sendKeyEvent() reaches that app like a keyboard key, with real
 *    down / up (holding works). The app must have a focused view that accepts input; Winlator and
 *    most emulators do (they support the Android keyboard).
 *  - TOUCH: delegated to [AccessibilityTouchBackend] (TAP buttons, TOUCH_DRAG sticks).
 * ESC falls back to the system Back action when no input connection is available.
 */
class NoRootBackend(private val context: android.content.Context) : InputBackend {
    override val id = "noroot"

    private val toucher = AccessibilityTouchBackend()
    private val svc: AccessibilityService? get() = OnaAccessibilityService.instance

    /** Down time of each held key, so the UP event matches its DOWN. */
    private val downTimes = HashMap<Int, Long>()
    private var meta = 0
    @Volatile private var lastKeyOk: Boolean? = null
    private var warned = false

    override fun supports(category: InputBackend.Capability) =
        category == InputBackend.Capability.TOUCH ||
            (Build.VERSION.SDK_INT >= 33 && (category == InputBackend.Capability.KEYBOARD || category == InputBackend.Capability.GAMEPAD))

    override fun status(): String {
        val keys = when {
            com.onalauncher.controls.shizuku.ShizukuKeys.ready -> "Keys ✓ (Shizuku)"
            Build.VERSION.SDK_INT < 33 -> "Keys: Android 13+"
            svc == null -> "Keys: accessibility off"
            lastKeyOk == false -> "Keys: app has no input focus"
            else -> "Keys ✓"
        }
        return "$keys · ${toucher.status()}"
    }

    override fun start() {
        com.onalauncher.controls.shizuku.ShizukuKeys.init(context)
        toucher.start()
    }
    override fun stop() {
        // never leave a key stuck down in the game
        downTimes.keys.toList().forEach { send(it, false) }
        toucher.stop()
    }

    override fun touch(pointer: Int, x: Float, y: Float, action: TouchAction) = toucher.touch(pointer, x, y, action)

    override fun key(key: OverlayKey, down: Boolean) {
        val code = key.androidCode
        if (code == KeyEvent.KEYCODE_UNKNOWN) return
        updateMeta(code, down)
        val ok = send(code, down)
        if (!ok && down && key == OverlayKey.ESC) svc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    override fun gamepadButton(key: OverlayKey, down: Boolean) = this.key(key, down)

    private fun updateMeta(code: Int, down: Boolean) {
        val bits = when (code) {
            KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
            KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
            KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON
            else -> return
        }
        meta = if (down) meta or bits else meta and bits.inv()
    }

    /** Returns true if the key reached an input connection. */
    private fun send(code: Int, down: Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        val downTime = if (down) now.also { downTimes[code] = it } else (downTimes.remove(code) ?: now)
        if (com.onalauncher.controls.shizuku.ShizukuKeys.key(code, down, meta, downTime)) {
            lastKeyOk = true
            return true
        }
        if (Build.VERSION.SDK_INT < 33) { warnOnce(); return false }
        val ev = KeyEvent(
            downTime, now,
            if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
            InputDevice.SOURCE_KEYBOARD
        )
        val ic = svc?.inputMethod?.currentInputConnection
        if (ic == null) {
            if (lastKeyOk != false) Log.w("OnaOverlay", "No input connection: the app in front has no focused input view")
            lastKeyOk = false
            warnOnce()
            return false
        }
        ic.sendKeyEvent(ev)
        lastKeyOk = true
        return true
    }

    /** Tell the user once per session why keys do nothing (instead of silence). */
    private fun warnOnce() {
        if (warned) return
        warned = true
        android.widget.Toast.makeText(context, com.onalauncher.controls.R.string.ona_overlay_keys_fail, android.widget.Toast.LENGTH_LONG).show()
    }
}
