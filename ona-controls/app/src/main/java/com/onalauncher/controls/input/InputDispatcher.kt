/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.input

import com.onalauncher.controls.model.GamepadAxis
import com.onalauncher.controls.model.KeyCategory
import com.onalauncher.controls.model.OverlayElement
import com.onalauncher.controls.model.OverlayKey
import com.onalauncher.controls.model.OverlayProfile

/**
 * Turns "this binding went down/up" into backend calls. Views never talk to a backend directly,
 * so a profile works the same with the PC bridge, accessibility touch or root.
 *
 * Keys pressed by two controls at once are reference counted: the key goes up only when the last
 * control lets go (a D-pad W plus a stick W must not release each other).
 */
class InputDispatcher(
    @Volatile var backend: InputBackend,
    private val profile: () -> OverlayProfile,
    private val screenSize: () -> Pair<Int, Int>
) {
    private val held = HashMap<OverlayKey, Int>()

    /** Called after every mouse move / click, so the service can move the drawn pointer. */
    var onMouse: (() -> Unit)? = null

    /** Mouse works only when the profile's mouse mode is SWIPE (and mouse input is not disabled). */
    fun mouseEnabled(): Boolean {
        val p = profile()
        return !p.disableMouse && p.mouseMode == com.onalauncher.controls.model.MouseMode.SWIPE
    }
    private var nextPointer = 1

    fun newPointerId(): Int = nextPointer++

    fun press(key: OverlayKey, down: Boolean, element: OverlayElement? = null, pointer: Int = 0) {
        if (key == OverlayKey.NONE) return
        if (key.category == KeyCategory.TOUCH) {
            val el = element ?: return
            val (w, h) = screenSize()
            backend.touch(pointer, el.tapX * w, el.tapY * h, if (down) TouchAction.DOWN else TouchAction.UP)
            return
        }
        val count = held[key] ?: 0
        if (down) {
            held[key] = count + 1
            if (count > 0) return
        } else {
            if (count <= 0) return
            if (count > 1) { held[key] = count - 1; return }
            held.remove(key)
        }
        when (key.category) {
            KeyCategory.KEYBOARD -> backend.key(key, down)
            KeyCategory.MOUSE -> if (mouseEnabled()) { backend.mouseButton(key, down); onMouse?.invoke() }
            KeyCategory.GAMEPAD -> backend.gamepadButton(key, down)
            else -> Unit
        }
    }

    fun mouseMove(dx: Float, dy: Float) {
        if (!mouseEnabled()) return
        val p = profile()
        backend.mouseMove(dx * p.cursorSpeed, dy * p.cursorSpeed)
        onMouse?.invoke()
    }

    fun wheel(steps: Int) {
        if (mouseEnabled()) backend.mouseWheel(steps)
    }

    fun axis(axis: GamepadAxis, value: Float) = backend.gamepadAxis(axis, value)

    fun touch(pointer: Int, xFrac: Float, yFrac: Float, action: TouchAction) {
        val (w, h) = screenSize()
        backend.touch(pointer, xFrac * w, yFrac * h, action)
    }

    /** Lets go of everything (overlay hidden, profile switched, service stopped). */
    fun releaseAll() {
        val keys = held.keys.toList()
        held.clear()
        keys.forEach { k ->
            when (k.category) {
                KeyCategory.KEYBOARD -> backend.key(k, false)
                KeyCategory.MOUSE -> if (mouseEnabled()) backend.mouseButton(k, false)
                KeyCategory.GAMEPAD -> backend.gamepadButton(k, false)
                else -> Unit
            }
        }
        GamepadAxis.entries.forEach { backend.gamepadAxis(it, 0f) }
    }
}
