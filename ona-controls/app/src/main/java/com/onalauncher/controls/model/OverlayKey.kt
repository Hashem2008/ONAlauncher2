/*
 * ONA Launcher 2 - floating overlay controls (Winlator-style input profiles over any app).
 * GPL-3.0, same as Zalith Launcher 2.
 */
package com.onalauncher.controls.model

import android.view.KeyEvent

/** What an overlay key talks to. The dispatcher routes each category to the active backend. */
enum class KeyCategory { NONE, KEYBOARD, MOUSE, GAMEPAD, TOUCH }

/**
 * Every bindable output. The enum NAME is the wire id sent to the PC bridge (stable, do not rename),
 * [label] is drawn on the control, [androidCode] is used by the root backend (`input keyevent`).
 */
enum class OverlayKey(
    val label: String,
    val category: KeyCategory,
    val androidCode: Int = KeyEvent.KEYCODE_UNKNOWN
) {
    NONE("NONE", KeyCategory.NONE),

    // ---- keyboard ----
    ESC("ESC", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_ESCAPE),
    ENTER("ENTER", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_ENTER),
    SPACE("SPACE", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_SPACE),
    TAB("TAB", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_TAB),
    BKSP("BKSP", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_DEL),
    DEL("DEL", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_FORWARD_DEL),
    INSERT("INS", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_INSERT),
    HOME("HOME", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_MOVE_HOME),
    END("END", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_MOVE_END),
    PAGE_UP("PGUP", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_PAGE_UP),
    PAGE_DOWN("PGDN", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_PAGE_DOWN),
    UP("↑", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_DPAD_UP),
    DOWN("↓", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT("←", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT("→", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_DPAD_RIGHT),
    L_SHIFT("L SHIFT", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_SHIFT_LEFT),
    L_CTRL("L CTRL", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_CTRL_LEFT),
    L_ALT("L ALT", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_ALT_LEFT),
    R_SHIFT("R SHIFT", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_SHIFT_RIGHT),
    R_CTRL("R CTRL", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_CTRL_RIGHT),
    R_ALT("R ALT", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_ALT_RIGHT),
    CAPS("CAPS", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_CAPS_LOCK),
    TILDE("~", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_GRAVE),
    MINUS("-", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_MINUS),
    EQUALS("=", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_EQUALS),
    COMMA(",", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_COMMA),
    PERIOD(".", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_PERIOD),
    SLASH("/", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_SLASH),
    F1("F1", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_F1),
    F2("F2", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_F2),
    F3("F3", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_F3),
    F4("F4", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_F4),
    F5("F5", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_F5),
    F6("F6", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_F6),
    F7("F7", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_F7),
    F8("F8", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_F8),
    F9("F9", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_F9),
    F10("F10", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_F10),
    F11("F11", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_F11),
    F12("F12", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_F12),
    K0("0", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_0),
    K1("1", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_1),
    K2("2", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_2),
    K3("3", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_3),
    K4("4", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_4),
    K5("5", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_5),
    K6("6", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_6),
    K7("7", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_7),
    K8("8", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_8),
    K9("9", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_9),
    A("A", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_A),
    B("B", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_B),
    C("C", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_C),
    D("D", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_D),
    E("E", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_E),
    F("F", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_F),
    G("G", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_G),
    H("H", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_H),
    I("I", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_I),
    J("J", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_J),
    K("K", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_K),
    L("L", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_L),
    M("M", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_M),
    N("N", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_N),
    O("O", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_O),
    P("P", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_P),
    Q("Q", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_Q),
    R("R", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_R),
    S("S", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_S),
    T("T", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_T),
    U("U", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_U),
    V("V", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_V),
    W("W", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_W),
    X("X", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_X),
    Y("Y", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_Y),
    Z("Z", KeyCategory.KEYBOARD, KeyEvent.KEYCODE_Z),

    // ---- mouse ----
    MLB("MLB", KeyCategory.MOUSE),
    MRB("MRB", KeyCategory.MOUSE),
    MMB("MMB", KeyCategory.MOUSE),
    WHEEL_UP("WHEEL ↑", KeyCategory.MOUSE),
    WHEEL_DOWN("WHEEL ↓", KeyCategory.MOUSE),

    // ---- virtual gamepad (XInput layout) ----
    GP_A("A", KeyCategory.GAMEPAD, KeyEvent.KEYCODE_BUTTON_A),
    GP_B("B", KeyCategory.GAMEPAD, KeyEvent.KEYCODE_BUTTON_B),
    GP_X("X", KeyCategory.GAMEPAD, KeyEvent.KEYCODE_BUTTON_X),
    GP_Y("Y", KeyCategory.GAMEPAD, KeyEvent.KEYCODE_BUTTON_Y),
    GP_LB("LB", KeyCategory.GAMEPAD, KeyEvent.KEYCODE_BUTTON_L1),
    GP_RB("RB", KeyCategory.GAMEPAD, KeyEvent.KEYCODE_BUTTON_R1),
    GP_LT("LT", KeyCategory.GAMEPAD, KeyEvent.KEYCODE_BUTTON_L2),
    GP_RT("RT", KeyCategory.GAMEPAD, KeyEvent.KEYCODE_BUTTON_R2),
    GP_SELECT("SELECT", KeyCategory.GAMEPAD, KeyEvent.KEYCODE_BUTTON_SELECT),
    GP_START("START", KeyCategory.GAMEPAD, KeyEvent.KEYCODE_BUTTON_START),
    GP_L3("L3", KeyCategory.GAMEPAD, KeyEvent.KEYCODE_BUTTON_THUMBL),
    GP_R3("R3", KeyCategory.GAMEPAD, KeyEvent.KEYCODE_BUTTON_THUMBR),
    GP_DPAD_UP("↑", KeyCategory.GAMEPAD, KeyEvent.KEYCODE_DPAD_UP),
    GP_DPAD_RIGHT("→", KeyCategory.GAMEPAD, KeyEvent.KEYCODE_DPAD_RIGHT),
    GP_DPAD_DOWN("↓", KeyCategory.GAMEPAD, KeyEvent.KEYCODE_DPAD_DOWN),
    GP_DPAD_LEFT("←", KeyCategory.GAMEPAD, KeyEvent.KEYCODE_DPAD_LEFT),

    // ---- touch on the target app (the element's own tap point) ----
    TOUCH_TAP("TAP", KeyCategory.TOUCH);

    companion object {
        /** Lenient lookup used by JSON import (unknown names become NONE instead of failing). */
        fun of(name: String?): OverlayKey = entries.firstOrNull { it.name == name } ?: NONE

        /** Names shown in the binding picker, grouped like Winlator's (keyboard, mouse, gamepad). */
        val pickerOrder: List<OverlayKey> by lazy {
            KeyCategory.entries.flatMap { cat -> OverlayKey.entries.filter { it.category == cat } }
        }
    }
}

/** Analog axes of the virtual gamepad (value range -1..1, triggers 0..1). */
enum class GamepadAxis { LX, LY, RX, RY, LT, RT }
