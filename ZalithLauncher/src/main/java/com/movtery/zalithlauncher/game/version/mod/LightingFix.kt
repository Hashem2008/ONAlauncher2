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
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File

/**
 * ONA: blocks turned black after being broken or placed.
 *
 * Sodium Extra and its Forge ports (Rubidium Extra, Embeddium Extra...) have a "Light updates"
 * switch that modpacks and "FPS guides" turn off. With it off the game never recomputes the light
 * around a changed block, so the hole left by a broken block stays at light 0 and draws black
 * (F3 then says "Light updates disabled"). The switch is put back on before every launch; the
 * mod itself is untouched and keeps every other setting. Light engines like Starlight make the
 * updates cheap, so the frame rate cost is small.
 */
object LightingFix {
    private const val TAG = "LightingFix"
    private val GSON = GsonBuilder().setPrettyPrinting().create()

    /** @return the config files that had light updates switched off and were fixed */
    fun apply(version: Version): List<String> {
        val configDir = File(version.getGameDir(), "config")
        val files = configDir.listFiles { file ->
            val name = file.name.lowercase()
            file.isFile && name.endsWith(".json") && name.contains("extra")
        } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching { if (fix(file)) file.name else null }
                .onFailure { Logger.warning(TAG, "Could not check ${file.name}", it) }
                .getOrNull()
        }
    }

    private fun fix(file: File): Boolean {
        val root = JsonParser.parseString(file.readText()).takeIf { it.isJsonObject }?.asJsonObject ?: return false
        val render = root.get("render_settings")?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        val value = render.get("light_updates")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return false
        if (!value.isBoolean || value.asBoolean) return false
        render.add("light_updates", JsonPrimitive(true))
        file.writeText(GSON.toJson(root))
        Logger.info(TAG, "Turned light updates back on in ${file.name} (broken blocks turned black)")
        return true
    }
}
