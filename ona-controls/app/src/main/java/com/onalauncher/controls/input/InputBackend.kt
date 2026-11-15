/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.input

import com.onalauncher.controls.model.GamepadAxis
import com.onalauncher.controls.model.OverlayKey

enum class TouchAction { DOWN, MOVE, UP }

/**
 * Where the overlay's input goes. Every call comes from the UI thread and must return at once:
 * implementations queue work to their own thread (sockets, su shell) or to the system (gestures).
 * Calls a backend cannot do are silently ignored ([supports] tells the UI which ones work).
 */
interface InputBackend {
    val id: String

    fun start() {}
    fun stop() {}

    /** Short human status for the notification ("connected 192.168.1.10:7788", "needs root" ...). */
    fun status(): String = ""

    fun supports(category: Capability): Boolean

    fun key(key: OverlayKey, down: Boolean) {}
    fun mouseButton(key: OverlayKey, down: Boolean) {}
    fun mouseMove(dx: Float, dy: Float) {}
    /** Puts the cursor at an absolute screen point (px); screen size given for backends that need fractions. */
    fun mouseTo(x: Float, y: Float, screenW: Int, screenH: Int) {}
    /** Where this backend's own mouse cursor is (screen px), null if it has none (to draw the pointer). */
    fun cursorPosition(): Pair<Float, Float>? = null
    fun mouseWheel(steps: Int) {}
    fun gamepadButton(key: OverlayKey, down: Boolean) {}
    fun gamepadAxis(axis: GamepadAxis, value: Float) {}

    /** A finger on the app under the overlay, absolute screen pixels. [pointer] is stable per gesture. */
    fun touch(pointer: Int, x: Float, y: Float, action: TouchAction) {}

    enum class Capability { KEYBOARD, MOUSE, GAMEPAD, TOUCH }
}

/** Used while nothing is configured: drops everything. */
object NoBackend : InputBackend {
    override val id = "none"
    override fun supports(category: InputBackend.Capability) = false
}
