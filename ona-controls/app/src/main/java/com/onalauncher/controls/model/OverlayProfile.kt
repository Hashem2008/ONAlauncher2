/*
 * ONA Launcher 2 - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.model

enum class ElementType { BUTTON, BUTTON_ROW, DPAD, STICK, TRACKPAD }

enum class ButtonShape { CIRCLE, SQUARE, RECT, PILL }

/** Mouse for a profile: OFF (no mouse at all) or SWIPE (cursor + floating touchpad, drag = move). */
enum class MouseMode { OFF, SWIPE }

/** What an analog stick produces. */
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
    var dragRadius: Float = 0.12f,
    /** Stretch: width and height multipliers on top of [scale] (1 = normal). */
    var width: Float = 1f,
    var height: Float = 1f,
    /** Outline / pressed colour (ARGB), 0 = default light grey. */
    var color: Int = 0
)

data class OverlayProfile(
    var id: String,
    var name: String,
    /** Mouse speed multiplier for trackpad / mouse stick (Winlator "Cursor Speed"), 0.1..3. */
    var cursorSpeed: Float = 1f,
    /** Overlay opacity (Winlator "Overlay Opacity"), 0.05..1. */
    var opacity: Float = 0.4f,
    /** Winlator "Disable Mouse Input": trackpads and mouse sticks send nothing. */
    var disableMouse: Boolean = false,
    /** Mouse: OFF (default, nothing mouse related runs) or SWIPE (cursor + floating touchpad). */
    var mouseMode: MouseMode = MouseMode.OFF,
    var elements: MutableList<OverlayElement> = mutableListOf()
) {
    fun deepCopy(newId: String = id, newName: String = name): OverlayProfile = copy(
        id = newId,
        name = newName,
        elements = elements.map { it.copy(bindings = it.bindings.toMutableList()) }.toMutableList()
    )
}

// ---------------------------------------------------------------- JSON (org.json, built into Android)

private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: def

fun OverlayElement.toJson(): org.json.JSONObject = org.json.JSONObject().apply {
    put("id", id); put("type", type.name); put("x", x.toDouble()); put("y", y.toDouble())
    put("scale", scale.toDouble()); put("shape", shape.name)
    put("bindings", org.json.JSONArray().apply { bindings.forEach { put(it.name) } })
    put("toggle", toggle); put("text", text); put("stickOutput", stickOutput.name)
    put("deadzone", deadzone.toDouble()); put("eightWay", eightWay)
    put("tapX", tapX.toDouble()); put("tapY", tapY.toDouble()); put("dragRadius", dragRadius.toDouble())
    put("width", width.toDouble()); put("height", height.toDouble()); put("color", color)
}

fun elementFromJson(o: org.json.JSONObject): OverlayElement {
    val arr = o.optJSONArray("bindings")
    val keys = MutableList(arr?.length() ?: 0) { i -> OverlayKey.of(arr!!.optString(i)) }
    return OverlayElement(
        id = o.optString("id", "e" + System.nanoTime()),
        type = enumOr(o.optString("type"), ElementType.BUTTON),
        x = o.optDouble("x", 0.5).toFloat(),
        y = o.optDouble("y", 0.5).toFloat(),
        scale = o.optDouble("scale", 1.0).toFloat(),
        shape = enumOr(o.optString("shape"), ButtonShape.CIRCLE),
        bindings = keys,
        toggle = o.optBoolean("toggle", false),
        text = o.optString("text", ""),
        stickOutput = enumOr(o.optString("stickOutput"), StickOutput.KEYS),
        deadzone = o.optDouble("deadzone", 0.15).toFloat(),
        eightWay = o.optBoolean("eightWay", true),
        tapX = o.optDouble("tapX", 0.5).toFloat(),
        tapY = o.optDouble("tapY", 0.5).toFloat(),
        dragRadius = o.optDouble("dragRadius", 0.12).toFloat(),
        width = o.optDouble("width", 1.0).toFloat(),
        height = o.optDouble("height", 1.0).toFloat(),
        color = o.optInt("color", 0)
    )
}

fun OverlayProfile.toJson(): org.json.JSONObject = org.json.JSONObject().apply {
    put("format", "ona-controls-profile-1")
    put("id", id); put("name", name); put("cursorSpeed", cursorSpeed.toDouble())
    put("opacity", opacity.toDouble()); put("disableMouse", disableMouse); put("mouseMode", mouseMode.name)
    put("elements", org.json.JSONArray().apply { elements.forEach { put(it.toJson()) } })
}

/** Throws if the text is not a profile (no "elements" array). */
fun profileFromJson(text: String): OverlayProfile {
    val o = org.json.JSONObject(text)
    val arr = o.getJSONArray("elements")
    return OverlayProfile(
        id = o.optString("id", "p" + System.nanoTime()),
        name = o.optString("name", "Profile"),
        cursorSpeed = o.optDouble("cursorSpeed", 1.0).toFloat(),
        opacity = o.optDouble("opacity", 0.4).toFloat(),
        disableMouse = o.optBoolean("disableMouse", false),
        mouseMode = enumOr(o.optString("mouseMode"), MouseMode.OFF),
        elements = MutableList(arr.length()) { i -> elementFromJson(arr.getJSONObject(i)) }
    )
}
