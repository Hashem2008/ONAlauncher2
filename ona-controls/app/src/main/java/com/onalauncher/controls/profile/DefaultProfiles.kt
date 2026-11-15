/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.profile

import com.onalauncher.controls.model.ButtonShape
import com.onalauncher.controls.model.ElementType
import com.onalauncher.controls.model.OverlayElement
import com.onalauncher.controls.model.OverlayKey
import com.onalauncher.controls.model.OverlayKey.*
import com.onalauncher.controls.model.OverlayProfile
import com.onalauncher.controls.model.StickOutput

/**
 * Built-in profiles, laid out like the Winlator ones the user showed (RTS, Template, FPS,
 * Virtual Gamepad). Coordinates are fractions of a landscape screen.
 */
object DefaultProfiles {
    const val RTS = "rts"
    const val TEMPLATE = "template12"
    const val FPS = "fps"
    const val GAMEPAD = "gamepad"
    const val GENERALS = "generals_zh"

    private var counter = 0
    private fun nid() = "e${counter++}"

    private fun button(x: Float, y: Float, key: OverlayKey, shape: ButtonShape = ButtonShape.SQUARE, scale: Float = 1f) =
        OverlayElement(nid(), ElementType.BUTTON, x, y, scale, shape, mutableListOf(key))

    private fun row(x: Float, y: Float, vararg keys: OverlayKey) =
        OverlayElement(nid(), ElementType.BUTTON_ROW, x, y, 1f, ButtonShape.SQUARE, keys.toMutableList())

    private fun dpad(x: Float, y: Float, up: OverlayKey, right: OverlayKey, down: OverlayKey, left: OverlayKey, eight: Boolean = true) =
        OverlayElement(nid(), ElementType.DPAD, x, y, 1f, bindings = mutableListOf(up, right, down, left), eightWay = eight)

    private fun stick(x: Float, y: Float, out: StickOutput, scale: Float = 1f) =
        OverlayElement(
            nid(), ElementType.STICK, x, y, scale,
            bindings = mutableListOf(W, D, S, A), stickOutput = out
        )

    fun all(): List<OverlayProfile> = listOf(generals(), rts(), template(), fps(), gamepad())

    /** A button with a picture and a short label under it. */
    private fun pic(
        x: Float, y: Float, key: OverlayKey, icon: String, text: String,
        scale: Float = 1f, toggle: Boolean = false, swipe: Boolean = true, moveMouse: Boolean = false
    ) = OverlayElement(
        nid(), ElementType.BUTTON, x, y, scale, ButtonShape.SQUARE, mutableListOf(key),
        toggle = toggle, text = text, icon = icon, showLabel = true,
        swipeTrigger = swipe && !toggle, moveWithMouse = moveMouse
    )

    /**
     * Command & Conquer Generals - Zero Hour (played in Winlator). Default game hotkeys:
     * 1-6 select a group (CTRL + number = make the group, SHIFT + click = add to the selection),
     * Q select all units, E select all of the same type, W select all aircraft, A attack-move,
     * S stop, X scatter, D build a dozer (Command Center selected), H go to the base,
     * SPACE jump to the last event, ESC menu, arrows scroll, wheel zoom, right-drag scrolls the map.
     * The command bar at the bottom of the game is left free. Keys can be changed in the editor.
     */
    fun generals() = OverlayProfile(
        GENERALS, "Generals Zero Hour", cursorSpeed = 1f, opacity = 0.55f, elements = mutableListOf(
            row(0.155f, 0.075f, K1, K2, K3, K4, K5, K6),
            pic(0.855f, 0.075f, H, "home", "HOME H", 0.85f),
            pic(0.910f, 0.075f, SPACE, "radar", "EVENT", 0.85f),
            pic(0.965f, 0.075f, ESC, "menu", "MENU", 0.85f, swipe = false),
            pic(0.045f, 0.22f, L_CTRL, "keyboard", "CTRL", toggle = true),
            pic(0.045f, 0.36f, L_SHIFT, "group", "SHIFT", toggle = true),
            dpad(0.10f, 0.62f, UP, RIGHT, DOWN, LEFT).apply { scale = 0.8f },
            pic(0.20f, 0.52f, WHEEL_UP, "zoom_in", "ZOOM+", 0.85f),
            pic(0.20f, 0.66f, WHEEL_DOWN, "zoom_out", "ZOOM-", 0.85f),
            OverlayElement(nid(), ElementType.TRACKPAD, 0.185f, 0.30f, 0.55f),
            pic(0.825f, 0.22f, D, "dozer", "DOZER D"),
            pic(0.890f, 0.22f, Q, "group", "ALL Q"),
            pic(0.890f, 0.36f, E, "same_type", "SAME E"),
            pic(0.890f, 0.50f, W, "aircraft", "AIR W"),
            pic(0.890f, 0.64f, MRB, "pan", "SCROLL", swipe = false, moveMouse = true),
            pic(0.955f, 0.22f, A, "crosshair", "ATK A"),
            pic(0.955f, 0.36f, S, "stop", "STOP S"),
            pic(0.955f, 0.50f, X, "scatter", "SCAT X"),
            pic(0.955f, 0.64f, RIGHT_TAP_MODE, "mouse_right", "R-CLICK", toggle = true)
        )
    )

    fun rts() = OverlayProfile(
        RTS, "RTS", elements = mutableListOf(
            row(0.155f, 0.087f, K1, K2, K3, K4, K5, K6),
            row(0.837f, 0.087f, A, B, C, D, E, F),
            button(0.068f, 0.352f, ESC), button(0.127f, 0.352f, DEL),
            button(0.068f, 0.484f, TAB), button(0.127f, 0.484f, UP),
            button(0.068f, 0.616f, LEFT), button(0.127f, 0.616f, RIGHT),
            button(0.068f, 0.748f, L_SHIFT), button(0.127f, 0.748f, DOWN),
            button(0.068f, 0.880f, L_CTRL), button(0.127f, 0.880f, L_ALT),
            button(0.866f, 0.748f, MRB), button(0.925f, 0.748f, BKSP),
            button(0.866f, 0.880f, SPACE), button(0.925f, 0.880f, ENTER)
        )
    )

    fun template() = OverlayProfile(
        TEMPLATE, "Template (12 buttons)", elements = mutableListOf(
            button(0.078f, 0.087f, NONE, ButtonShape.RECT), button(0.175f, 0.087f, NONE, ButtonShape.RECT),
            button(0.818f, 0.087f, NONE, ButtonShape.RECT), button(0.915f, 0.087f, NONE, ButtonShape.RECT),
            button(0.068f, 0.439f, NONE, ButtonShape.CIRCLE), button(0.925f, 0.439f, NONE, ButtonShape.CIRCLE),
            dpad(0.107f, 0.726f, NONE, NONE, NONE, NONE),
            button(0.866f, 0.594f, NONE, ButtonShape.CIRCLE, 1.15f), button(0.808f, 0.726f, NONE, ButtonShape.CIRCLE, 1.15f),
            button(0.925f, 0.726f, NONE, ButtonShape.CIRCLE, 1.15f), button(0.866f, 0.858f, NONE, ButtonShape.CIRCLE, 1.15f),
            button(0.457f, 0.902f, NONE, ButtonShape.PILL), button(0.535f, 0.902f, NONE, ButtonShape.PILL)
        )
    )

    fun fps() = OverlayProfile(
        FPS, "FPS", elements = mutableListOf(
            row(0.109f, 0.087f, K1, K2, K3, K4),
            row(0.883f, 0.087f, F1, F2, F3, F4),
            stick(0.089f, 0.330f, StickOutput.KEYS, 0.7f),
            stick(0.904f, 0.330f, StickOutput.MOUSE, 0.7f),
            button(0.188f, 0.528f, MRB, ButtonShape.CIRCLE, 1.15f),
            dpad(0.100f, 0.726f, W, D, S, A),
            button(0.248f, 0.880f, L_CTRL, ButtonShape.CIRCLE), button(0.318f, 0.880f, TAB, ButtonShape.CIRCLE),
            button(0.457f, 0.902f, ESC, ButtonShape.PILL), button(0.537f, 0.902f, ENTER, ButtonShape.PILL),
            button(0.676f, 0.880f, L_ALT, ButtonShape.CIRCLE), button(0.745f, 0.880f, L_SHIFT, ButtonShape.CIRCLE),
            button(0.874f, 0.594f, R, ButtonShape.CIRCLE, 1.15f), button(0.814f, 0.726f, MLB, ButtonShape.CIRCLE, 1.15f),
            button(0.933f, 0.726f, E, ButtonShape.CIRCLE, 1.15f), button(0.874f, 0.858f, SPACE, ButtonShape.CIRCLE, 1.15f)
        )
    )

    fun gamepad() = OverlayProfile(
        GAMEPAD, "Virtual Gamepad", elements = mutableListOf(
            button(0.078f, 0.087f, GP_LT, ButtonShape.RECT), button(0.175f, 0.087f, GP_LB, ButtonShape.RECT),
            button(0.818f, 0.087f, GP_RB, ButtonShape.RECT), button(0.915f, 0.087f, GP_RT, ButtonShape.RECT),
            stick(0.110f, 0.420f, StickOutput.GAMEPAD_LEFT, 0.85f),
            dpad(0.230f, 0.760f, GP_DPAD_UP, GP_DPAD_RIGHT, GP_DPAD_DOWN, GP_DPAD_LEFT, eight = false).apply { scale = 0.8f },
            stick(0.740f, 0.760f, StickOutput.GAMEPAD_RIGHT, 0.85f),
            button(0.900f, 0.420f, GP_Y, ButtonShape.CIRCLE, 1.1f), button(0.845f, 0.540f, GP_X, ButtonShape.CIRCLE, 1.1f),
            button(0.955f, 0.540f, GP_B, ButtonShape.CIRCLE, 1.1f), button(0.900f, 0.660f, GP_A, ButtonShape.CIRCLE, 1.1f),
            button(0.457f, 0.902f, GP_SELECT, ButtonShape.PILL), button(0.537f, 0.902f, GP_START, ButtonShape.PILL)
        )
    )
}
