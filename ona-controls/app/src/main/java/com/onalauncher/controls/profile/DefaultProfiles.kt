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
    const val GZH_ALL = "generals_zh_all"
    const val GZH_USA = "generals_zh_usa"

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

    fun all(): List<OverlayProfile> = listOf(rts(), template(), fps(), gamepad(), generalsAll(), generalsUsa())

    /** Button with its own text (two lines with \\n) and one or more keys pressed together (e.g. CTRL+H). */
    private fun g(x: Float, y: Float, text: String, shape: ButtonShape, scale: Float, toggle: Boolean, vararg keys: OverlayKey) =
        OverlayElement(nid(), ElementType.BUTTON, x, y, scale, shape, keys.toMutableList(), toggle = toggle, text = text)

    /**
     * C&C Generals Zero Hour, every army. Hotkeys from the lists the user sent (Steam guide +
     * gamereplays.org): groups 1-0 (CTRL+number = make, ALT+number = view), camera bookmarks F1-F8
     * (CTRL+F = set), SHIFT+click add, CTRL+click force fire, ALT+click waypoints, A attack-move,
     * G guard, S stop, X scatter, Q all combat units, E same type, W air (ZH), CTRL+H hero, CTRL+F
     * formation, H command center, SPACE last radar event, CTRL+arrows previous/next unit / dozer,
     * arrows scroll, numpad 8/2 zoom, 4/6 rotate, 5 reset camera, TAB, F9, F12, ESC, ENTER /
     * BACKSPACE chat, CTRL+B beacon, CTRL+C cheer. The game's command bar at the bottom stays free.
     */
    fun generalsAll() = OverlayProfile(
        GZH_ALL, "Generals ZH (all armies)", opacity = 0.55f, elements = mutableListOf(
            row(0.21f, 0.07f, K1, K2, K3, K4, K5, K6, K7, K8, K9, K0).apply { scale = 0.85f },
            row(0.17f, 0.175f, F1, F2, F3, F4, F5, F6, F7, F8).apply { scale = 0.85f },
            g(0.48f, 0.07f, "CHAT", ButtonShape.PILL, 0.9f, false, ENTER),
            g(0.555f, 0.07f, "TEAM", ButtonShape.PILL, 0.9f, false, BKSP),
            g(0.63f, 0.07f, "BEACON", ButtonShape.PILL, 0.9f, false, L_CTRL, B),
            g(0.705f, 0.07f, "CHEER", ButtonShape.PILL, 0.9f, false, L_CTRL, C),
            g(0.79f, 0.07f, "TAB", ButtonShape.SQUARE, 0.85f, false, TAB),
            g(0.845f, 0.07f, "BAR\nF9", ButtonShape.SQUARE, 0.85f, false, F9),
            g(0.9f, 0.07f, "SHOT\nF12", ButtonShape.SQUARE, 0.85f, false, F12),
            g(0.955f, 0.07f, "MENU\nESC", ButtonShape.SQUARE, 0.85f, false, ESC),
            g(0.045f, 0.31f, "CTRL", ButtonShape.SQUARE, 1.0f, true, L_CTRL),
            g(0.11f, 0.31f, "SHIFT", ButtonShape.SQUARE, 1.0f, true, L_SHIFT),
            g(0.175f, 0.31f, "ALT", ButtonShape.SQUARE, 1.0f, true, L_ALT),
            dpad(0.1f, 0.6f, UP, RIGHT, DOWN, LEFT).apply { scale = 0.8f },
            g(0.26f, 0.47f, "ZOOM+", ButtonShape.SQUARE, 0.85f, false, NUM_8),
            g(0.26f, 0.7f, "ZOOM-", ButtonShape.SQUARE, 0.85f, false, NUM_2),
            g(0.205f, 0.585f, "ROT\n◄", ButtonShape.SQUARE, 0.85f, false, NUM_4),
            g(0.315f, 0.585f, "ROT\n►", ButtonShape.SQUARE, 0.85f, false, NUM_6),
            g(0.26f, 0.585f, "CAM\nRESET", ButtonShape.SQUARE, 0.85f, false, NUM_5),
            g(0.955f, 0.22f, "A\nATTACK", ButtonShape.SQUARE, 1.0f, false, A),
            g(0.955f, 0.36f, "G\nGUARD", ButtonShape.SQUARE, 1.0f, false, G),
            g(0.955f, 0.5f, "S\nSTOP", ButtonShape.SQUARE, 1.0f, false, S),
            g(0.955f, 0.64f, "X\nSCATTER", ButtonShape.SQUARE, 1.0f, false, X),
            g(0.89f, 0.22f, "Q\nALL", ButtonShape.SQUARE, 1.0f, false, Q),
            g(0.89f, 0.36f, "E\nSAME", ButtonShape.SQUARE, 1.0f, false, E),
            g(0.89f, 0.5f, "W\nAIR", ButtonShape.SQUARE, 1.0f, false, W),
            g(0.89f, 0.64f, "HERO\nCTRL+H", ButtonShape.SQUARE, 1.0f, false, L_CTRL, H),
            g(0.825f, 0.22f, "H\nBASE", ButtonShape.SQUARE, 1.0f, false, H),
            g(0.825f, 0.36f, "SPACE\nEVENT", ButtonShape.SQUARE, 1.0f, false, SPACE),
            g(0.825f, 0.5f, "FORM\nCTRL+F", ButtonShape.SQUARE, 1.0f, false, L_CTRL, F),
            g(0.76f, 0.22f, "◄ UNIT\nCTRL+←", ButtonShape.SQUARE, 1.0f, false, L_CTRL, LEFT),
            g(0.76f, 0.36f, "UNIT ►\nCTRL+→", ButtonShape.SQUARE, 1.0f, false, L_CTRL, RIGHT),
            g(0.76f, 0.5f, "▲ DOZER\nCTRL+↑", ButtonShape.SQUARE, 1.0f, false, L_CTRL, UP),
            g(0.76f, 0.64f, "▼ DOZER\nCTRL+↓", ButtonShape.SQUARE, 1.0f, false, L_CTRL, DOWN)
        )
    )

    /**
     * Generals Zero Hour, USA: the main commands + every USA building / unit hotkey (they work while
     * a dozer or the right building is selected; one letter can mean several things, e.g. A = War
     * Factory with a dozer, Ranger in the barracks, Aurora at the airfield).
     */
    fun generalsUsa() = OverlayProfile(
        GZH_USA, "Generals ZH USA", opacity = 0.55f, elements = mutableListOf(
            row(0.21f, 0.07f, K1, K2, K3, K4, K5, K6, K7, K8, K9, K0).apply { scale = 0.85f },
            g(0.845f, 0.07f, "H\nBASE", ButtonShape.SQUARE, 0.85f, false, H),
            g(0.9f, 0.07f, "SPACE\nEVENT", ButtonShape.SQUARE, 0.85f, false, SPACE),
            g(0.955f, 0.07f, "MENU\nESC", ButtonShape.SQUARE, 0.85f, false, ESC),
            g(0.045f, 0.2f, "CTRL", ButtonShape.SQUARE, 1.0f, true, L_CTRL),
            g(0.11f, 0.2f, "SHIFT", ButtonShape.SQUARE, 1.0f, true, L_SHIFT),
            g(0.175f, 0.2f, "ALT", ButtonShape.SQUARE, 1.0f, true, L_ALT),
            dpad(0.1f, 0.6f, UP, RIGHT, DOWN, LEFT).apply { scale = 0.8f },
            g(0.235f, 0.33f, "Q\nALL", ButtonShape.SQUARE, 1.0f, false, Q),
            g(0.235f, 0.465f, "E\nSAME", ButtonShape.SQUARE, 1.0f, false, E),
            g(0.235f, 0.6f, "W\nAIR", ButtonShape.SQUARE, 1.0f, false, W),
            g(0.3f, 0.33f, "A\nATTACK", ButtonShape.SQUARE, 1.0f, false, A),
            g(0.3f, 0.465f, "G\nGUARD", ButtonShape.SQUARE, 1.0f, false, G),
            g(0.3f, 0.6f, "S\nSTOP", ButtonShape.SQUARE, 1.0f, false, S),
            g(0.365f, 0.33f, "X\nSCATTER", ButtonShape.SQUARE, 1.0f, false, X),
            g(0.365f, 0.465f, "FORM\nCTRL+F", ButtonShape.SQUARE, 1.0f, false, L_CTRL, F),
            g(0.365f, 0.6f, "HERO\nCTRL+H", ButtonShape.SQUARE, 1.0f, false, L_CTRL, H),
            g(0.73f, 0.19f, "C  CMD CENTER\nCrusader, Chinook", ButtonShape.RECT, 1.3f, false, C),
            g(0.73f, 0.315f, "R  REACTOR\nDrone Armour", ButtonShape.RECT, 1.3f, false, R),
            g(0.73f, 0.44f, "B  BARRACKS\nCol. Burton", ButtonShape.RECT, 1.3f, false, B),
            g(0.73f, 0.565f, "U  SUPPLY CTR", ButtonShape.RECT, 1.3f, false, U),
            g(0.73f, 0.69f, "M  PATRIOT\nMissile Defender", ButtonShape.RECT, 1.3f, false, M),
            g(0.838f, 0.19f, "A  FACTORY\nRanger, Aurora", ButtonShape.RECT, 1.3f, false, A),
            g(0.838f, 0.315f, "F  AIRFIELD\nStealth Fighter", ButtonShape.RECT, 1.3f, false, F),
            g(0.838f, 0.44f, "Y  STRATEGY", ButtonShape.RECT, 1.3f, false, Y),
            g(0.838f, 0.565f, "Z  DROP ZONE", ButtonShape.RECT, 1.3f, false, Z),
            g(0.838f, 0.69f, "D  DETENTION\nSearch & Destroy", ButtonShape.RECT, 1.3f, false, D),
            g(0.946f, 0.19f, "P  PARTICLE\nPathfinder, Paladin", ButtonShape.RECT, 1.3f, false, P),
            g(0.946f, 0.315f, "V  HUMVEE", ButtonShape.RECT, 1.3f, false, V),
            g(0.946f, 0.44f, "T  TOMAHAWK\nRaptor", ButtonShape.RECT, 1.3f, false, T),
            g(0.946f, 0.565f, "L  LASER MSL", ButtonShape.RECT, 1.3f, false, L),
            g(0.946f, 0.69f, "O  HOLD LINE", ButtonShape.RECT, 1.3f, false, O)
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
