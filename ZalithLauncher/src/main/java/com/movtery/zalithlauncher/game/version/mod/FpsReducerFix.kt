/*
 * ONA Launcher 2 (based on Zalith Launcher 2)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.version.mod

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File

/**
 * ONA: FPS Reducer (bre2el, "fpsreducer") held a 1.12.2 pack at 4 FPS on a phone.
 *
 * Its "Reduce FPS when inactive" asks LWJGL's Display.isActive() whether the game window has
 * the focus; under the launcher's GLFW bridge that answer is not reliable, so the mod thinks the
 * game is in the background and drops to its idle frame rate (the "FPS: 4" / "ECO:" on its HUD),
 * and "Suppress sound when inactive" mutes it the same way. On a phone the game is always in
 * front while it is played (Android pauses it otherwise), so both are switched off in the mod's
 * config before every launch. The mod stays on with every other setting, including the idle
 * reduction after the player stops touching the screen.
 *
 * Forge config keys checked in FpsReducer-mc1.12.2-1.20 (CommonConfig): `B:reducingInBackground`,
 * `B:suppressSound`, in config/fpsreducer.cfg and the per-player files in config/fpsreducer/.
 *
 * Dynamic FPS (Fabric / Forge / NeoForge) does the same from GLFW's focus and hover state, which
 * a touch screen does not have: its "unfocused" (1 FPS) and "hovered" (60 FPS) caps are lifted in
 * config/dynamic_fps.json (keys from its default_config.json, v3: states.<state>.frame_rate_target,
 * -1 = no cap; v2: reduceFPSWhenUnfocused). Idle, battery and hidden window handling stay.
 */
object FpsReducerFix {
    private const val TAG = "FpsReducerFix"

    private val KEYS = listOf("reducingInBackground", "suppressSound")

    /** @return the config files that were fixed */
    fun apply(version: Version): List<String> {
        val configDir = File(version.getGameDir(), "config")
        val files = buildList {
            configDir.listFiles { file -> file.isFile && file.name.lowercase().startsWith("fpsreducer") && file.name.endsWith(".cfg") }
                ?.let { addAll(it) }
            File(configDir, "fpsreducer").listFiles { file -> file.isFile && file.name.endsWith(".cfg") }
                ?.let { addAll(it) }
        }
        val fixed = files.mapNotNull { file ->
            runCatching { if (fix(file)) file.name else null }
                .onFailure { Logger.warning(TAG, "Could not check ${file.name}", it) }
                .getOrNull()
        }
        val dynamicFps = File(configDir, "dynamic_fps.json")
        val dynamicFixed = dynamicFps.isFile && runCatching { fixDynamicFps(dynamicFps) }
            .onFailure { Logger.warning(TAG, "Could not check dynamic_fps.json", it) }
            .getOrDefault(false)
        return if (dynamicFixed) fixed + dynamicFps.name else fixed
    }

    private fun fixDynamicFps(file: File): Boolean {
        val root = JsonParser.parseString(file.readText()).takeIf { it.isJsonObject }?.asJsonObject ?: return false
        var changed = false
        // v3
        root.get("states")?.takeIf { it.isJsonObject }?.asJsonObject?.let { states ->
            listOf("unfocused", "hovered").forEach { state ->
                val obj: JsonObject = states.get(state)?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val target = obj.get("frame_rate_target")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@forEach
                if (target >= 0) {
                    obj.add("frame_rate_target", JsonPrimitive(-1))
                    changed = true
                }
            }
        }
        // v2
        if (root.get("reduceFPSWhenUnfocused")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
            root.add("reduceFPSWhenUnfocused", JsonPrimitive(false))
            changed = true
        }
        if (!changed) return false
        file.writeText(GsonBuilder().setPrettyPrinting().create().toJson(root))
        Logger.info(TAG, "dynamic_fps.json: no FPS cap from the focus / hover state a phone does not have")
        return true
    }

    private fun fix(file: File): Boolean {
        val original = file.readText()
        var text = original
        KEYS.forEach { key ->
            text = Regex("(?m)^([ \\t]*B:$key[ \\t]*=[ \\t]*)true[ \\t]*$").replace(text) { it.groupValues[1] + "false" }
        }
        if (text == original) return false
        file.writeText(text)
        Logger.info(TAG, "${file.name}: FPS Reducer no longer treats the phone as inactive (it held the game at its idle FPS)")
        return true
    }
}
