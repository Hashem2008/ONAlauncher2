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
 * renders below screen resolution (the upscaler brings it back to full size). Only for the
 * launch that has a pack switched on; the user's own resolution is kept when it is already lower.
 * Frame generation stays the user's choice (it is never switched on from here). Setting "onaShaderBoost" (default on).
 */
object ShaderBoost {
    private const val TAG = "ShaderBoost"

    /** Highest render resolution (percent of the screen) while a shader pack is on, 0 = none */
    @Volatile
    var resolutionCap: Int = 0
        private set

    /** Why, for the launch log */
    @Volatile
    var summary: String = "off"
        private set

    /** The game should go through the upscaler although the user left it off (auto settings) */
    @Volatile
    var wantsUpscaler: Boolean = false
        private set

    fun prepare(gameDir: File) {
        resolutionCap = 0
        wantsUpscaler = false
        summary = "off"
        val tier = DeviceProfiler.current()?.tier ?: DeviceTier.MID
        val parts = mutableListOf<String>()
        // ONA auto settings: a render resolution the device carries, sharpened back by the upscaler
        if (AllSettings.autoSettings.getValue()) {
            val base = when (tier) {
                DeviceTier.LOW -> 55
                DeviceTier.MID -> 65
                DeviceTier.HIGH -> 85
                DeviceTier.FLAGSHIP -> 0
            }
            if (base > 0) {
                resolutionCap = base
                wantsUpscaler = true
                parts.add("auto settings ($tier)")
            }
        }
        if (AllSettings.shaderBoost.getValue()) {
            var pack = activeShaderPack(gameDir)
            // ONA's own pack is made for phones: without shadows it costs about nothing (no lower
            // resolution needed), with shadows it gets a milder cap than a desktop pack
            var lighter = 0
            if (pack == com.movtery.zalithlauncher.game.graphics.OnaGraphics.SHADER_NAME) {
                val shadows = runCatching {
                    File(gameDir, "shaderpacks/${pack}.txt").readLines()
                        .any { it.trim() == "SHADOWS=true" }
                }.getOrDefault(true)
                if (!shadows) pack = null else lighter = 15
            }
            // Voxy draws far terrain through Zink (desktop GL on Vulkan), every pixel costs more there
            val voxy = File(gameDir, "mods").listFiles()?.any { it.name.lowercase().startsWith("voxy") && it.name.endsWith(".jar") } == true
            if (pack != null || voxy) {
                val cap = when (tier) {
                    DeviceTier.FLAGSHIP -> if (pack != null) 70 else 75
                    DeviceTier.HIGH -> if (pack != null) 60 else 65
                    DeviceTier.MID -> if (pack != null) 50 else 55
                    DeviceTier.LOW -> if (pack != null) 45 else 50
                }
                val packCap = minOf(cap + (if (pack != null) lighter else 0), 100)
                resolutionCap = if (resolutionCap > 0) minOf(resolutionCap, packCap) else packCap
                parts.add(pack ?: "Voxy")
            }
        }
        if (resolutionCap > 0) {
            summary = "${parts.joinToString(" + ")}: resolution at most $resolutionCap%" +
                if (wantsUpscaler) ", upscaler on" else ""
            Logger.info(TAG, summary)
        }
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
