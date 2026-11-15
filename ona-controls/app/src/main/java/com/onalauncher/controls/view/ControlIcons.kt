/*
 * ONA Controls - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.view

import com.onalauncher.controls.R

/** Built-in pictures for controls (white vectors in res/drawable/ctl_*.xml, tinted when drawn). */
object ControlIcons {
    /** id -> (drawable, name shown in the picker). The id is what profiles store. */
    val all: List<Triple<String, Int, String>> = listOf(
        Triple("dozer", R.drawable.ctl_dozer, "Dozer / builder"),
        Triple("tank", R.drawable.ctl_tank, "Tank"),
        Triple("soldier", R.drawable.ctl_soldier, "Soldier"),
        Triple("aircraft", R.drawable.ctl_aircraft, "Aircraft"),
        Triple("group", R.drawable.ctl_group, "Group / select all"),
        Triple("same_type", R.drawable.ctl_same_type, "Same type"),
        Triple("crosshair", R.drawable.ctl_crosshair, "Attack / aim"),
        Triple("stop", R.drawable.ctl_stop, "Stop"),
        Triple("scatter", R.drawable.ctl_scatter, "Scatter"),
        Triple("pan", R.drawable.ctl_pan, "Move / scroll"),
        Triple("home", R.drawable.ctl_home, "Home / base"),
        Triple("radar", R.drawable.ctl_radar, "Radar / event"),
        Triple("menu", R.drawable.ctl_menu, "Menu"),
        Triple("mouse_left", R.drawable.ctl_mouse_left, "Mouse left"),
        Triple("mouse_right", R.drawable.ctl_mouse_right, "Mouse right"),
        Triple("mouse_middle", R.drawable.ctl_mouse_middle, "Mouse middle"),
        Triple("zoom_in", R.drawable.ctl_zoom_in, "Zoom in"),
        Triple("zoom_out", R.drawable.ctl_zoom_out, "Zoom out"),
        Triple("power", R.drawable.ctl_power, "Power"),
        Triple("shield", R.drawable.ctl_shield, "Shield / guard"),
        Triple("warning", R.drawable.ctl_warning, "Warning / superweapon"),
        Triple("repair", R.drawable.ctl_repair, "Repair"),
        Triple("flag", R.drawable.ctl_flag, "Flag / rally"),
        Triple("star", R.drawable.ctl_star, "Star / general"),
        Triple("keyboard", R.drawable.ctl_keyboard, "Keyboard")
    )

    fun res(id: String): Int? = all.firstOrNull { it.first == id }?.second
}
