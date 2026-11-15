/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.movtery.zalithlauncher.overlay.model

import kotlinx.serialization.Serializable

@Serializable
enum class ElementType { BUTTON, BUTTON_ROW, DPAD, STICK, TRACKPAD }

@Serializable
enum class ButtonShape { CIRCLE, SQUARE, RECT, PILL }

/** What an analog stick produces. */
@Serializable
enum class StickOutput {
    /** 8-way digital keys from [OverlayElement.bindings] = up, right, down, left (WASD by default). */
    KEYS,
    GAMEPAD_LEFT,
    GAMEPAD_RIGHT,
    /** Relative mouse movement while held (camera look). */
    MOUSE,
    /** Drags a finger around [OverlayElement.tapX]/[OverlayElement.tapY] on the target app (its own on-screen stick). */
    TOUCH_DRAG
}

/**
 * One control. Position is the CENTER as a fraction of the screen (0..1), so a profile fits any
 * screen size and both orientations. Size = base size of the type (dp) * [scale].
 *
 * Bindings per type:
 *  BUTTON      -> [0] (and [1] pressed together if set, e.g. CTRL+C)
 *  BUTTON_ROW  -> one per cell (Winlator "1 2 3 4 5 6" bars)
 *  DPAD, STICK -> up, right, down, left
 *  TRACKPAD    -> unused (tap = MLB, two-finger tap = MRB)
 */
@Serializable
data class OverlayElement(
    var id: String,
    var type: ElementType,
    var x: Float,
    var y: Float,
    var scale: Float = 1f,
    var shape: ButtonShape = ButtonShape.CIRCLE,
    var bindings: MutableList<OverlayKey> = mutableListOf(),
    /** BUTTON: stays down until tapped again. */
    var toggle: Boolean = false,
    /** Custom text, else the binding labels are drawn. */
    var text: String = "",
    // stick / dpad
    var stickOutput: StickOutput = StickOutput.KEYS,
    /** Fraction of the radius ignored around the centre (0..0.9). */
    var deadzone: Float = 0.15f,
    var eightWay: Boolean = true,
    // touch target on the app under the overlay (fractions of the screen), for TOUCH_TAP / TOUCH_DRAG
    var tapX: Float = 0.5f,
    var tapY: Float = 0.5f,
    /** TOUCH_DRAG radius as a fraction of the screen height. */
    var dragRadius: Float = 0.12f
)

@Serializable
data class OverlayProfile(
    var id: String,
    var name: String,
    /** Mouse speed multiplier for trackpad / mouse stick (Winlator "Cursor Speed"), 0.1..3. */
    var cursorSpeed: Float = 1f,
    /** Overlay opacity (Winlator "Overlay Opacity"), 0.05..1. */
    var opacity: Float = 0.4f,
    /** Winlator "Disable Mouse Input": trackpads and mouse sticks send nothing. */
    var disableMouse: Boolean = false,
    var elements: MutableList<OverlayElement> = mutableListOf()
) {
    fun deepCopy(newId: String = id, newName: String = name): OverlayProfile = copy(
        id = newId,
        name = newName,
        elements = elements.map { it.copy(bindings = it.bindings.toMutableList()) }.toMutableList()
    )
}
