/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.movtery.zalithlauncher.overlay.input

import com.movtery.zalithlauncher.overlay.model.GamepadAxis
import com.movtery.zalithlauncher.overlay.model.KeyCategory
import com.movtery.zalithlauncher.overlay.model.OverlayElement
import com.movtery.zalithlauncher.overlay.model.OverlayKey
import com.movtery.zalithlauncher.overlay.model.OverlayProfile

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
            KeyCategory.MOUSE -> if (!profile().disableMouse) backend.mouseButton(key, down)
            KeyCategory.GAMEPAD -> backend.gamepadButton(key, down)
            else -> Unit
        }
    }

    fun mouseMove(dx: Float, dy: Float) {
        val p = profile()
        if (p.disableMouse) return
        backend.mouseMove(dx * p.cursorSpeed, dy * p.cursorSpeed)
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
                KeyCategory.MOUSE -> backend.mouseButton(k, false)
                KeyCategory.GAMEPAD -> backend.gamepadButton(k, false)
                else -> Unit
            }
        }
        GamepadAxis.entries.forEach { backend.gamepadAxis(it, 0f) }
    }
}
