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

package com.movtery.zalithlauncher.utils.device

import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File

/**
 * ONA: shader boost. A shader pack draws every pixel many times, so with one active the game
 * renders below screen resolution (the upscaler brings it back to full size) and frame
 * generation doubles the frames shown. Only for the launch that has a pack switched on; the
 * user's own resolution is kept when it is already lower. Setting "onaShaderBoost" (default on).
 */
object ShaderBoost {
    private const val TAG = "ShaderBoost"

    /** Highest render resolution (percent of the screen) while a shader pack is on, 0 = none */
    @Volatile
    var resolutionCap: Int = 0
        private set

    /** Frame generation forced on for this launch */
    @Volatile
    var frameGeneration: Boolean = false
        private set

    /** Why, for the launch log */
    @Volatile
    var summary: String = "off"
        private set

    fun prepare(gameDir: File) {
        resolutionCap = 0
        frameGeneration = false
        summary = "off"
        if (!AllSettings.shaderBoost.getValue()) return
        val pack = activeShaderPack(gameDir) ?: return
        val tier = DeviceProfiler.current()?.tier ?: DeviceTier.MID
        resolutionCap = when (tier) {
            DeviceTier.FLAGSHIP -> 70
            DeviceTier.HIGH -> 60
            DeviceTier.MID -> 50
            DeviceTier.LOW -> 45
        }
        frameGeneration = true
        summary = "$pack: resolution at most $resolutionCap%, frame generation on"
        Logger.info(TAG, summary)
    }

    /** The render percentage to use: the user's, capped while a shader pack is on */
    fun percentage(userPercentage: Int): Int =
        if (resolutionCap > 0) minOf(userPercentage, resolutionCap) else userPercentage

    /** Name of the shader pack the game will start with, or null when shaders are off */
    fun activeShaderPack(gameDir: File): String? = runCatching {
        val config = File(gameDir, "config")
        for (name in listOf("iris.properties", "oculus.properties")) {
            val file = File(config, name)
            if (!file.isFile) continue
            val props = file.readLines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0 || line.startsWith("#")) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.toMap()
            val pack = props["shaderPack"]?.takeIf { it.isNotBlank() } ?: continue
            if (props["enableShaders"] == "true" && File(gameDir, "shaderpacks/$pack").exists()) return@runCatching pack
        }
        // OptiFine
        File(gameDir, "optionsshaders.txt").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("shaderPack=") }
            ?.substringAfter('=')?.trim()
            ?.takeIf { it.isNotBlank() && it != "OFF" && it != "(internal)" && File(gameDir, "shaderpacks/$it").exists() }
    }.getOrNull()
}
