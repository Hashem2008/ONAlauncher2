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
        return files.mapNotNull { file ->
            runCatching { if (fix(file)) file.name else null }
                .onFailure { Logger.warning(TAG, "Could not check ${file.name}", it) }
                .getOrNull()
        }
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
