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

package com.movtery.zalithlauncher.game.graphics

import com.movtery.zalithlauncher.game.renderer.renderers.SmartRenderer
import com.movtery.zalithlauncher.utils.device.ShaderBoost
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File
import java.util.zip.ZipFile

/**
 * Shader pack options that only work with compute shaders, images and big shader storage buffers
 * (Complementary's colored lighting and world space reflections ask for SSBOs of up to 810 MB).
 * When the engine runs the pack without compute (SmartRenderer.gl43Safe false), Iris refuses the
 * pack if such an option is on ("Shader storage buffers ... not supported"), so they are set off in
 * the pack's options file before the game starts. Every other option stays as the player set it.
 */
object ShaderPackCompat {
    private const val TAG = "ShaderPackCompat"

    /** option -> the value that turns the feature off */
    private val NEEDS_COMPUTE = linkedMapOf(
        "COLORED_LIGHTING" to "0",
        "WORLD_SPACE_REFLECTIONS" to "-1",
        "WORLD_SPACE_PLAYER_REF" to "-1"
    )

    /** @return the options set off now (empty when nothing changed) */
    fun apply(gameDir: File): List<String> = runCatching {
        val name = ShaderBoost.activeShaderPack(gameDir) ?: return@runCatching emptyList()
        if (OnaGraphics.isOnaPack(name)) return@runCatching emptyList()
        val pack = File(File(gameDir, "shaderpacks"), name)
        if (!pack.exists() || SmartRenderer.gl43Safe(pack)) return@runCatching emptyList()
        val declared = declaredOptions(pack)
        val wanted = NEEDS_COMPUTE.filterKeys { it in declared }
        if (wanted.isEmpty()) return@runCatching emptyList()
        val optionsFile = File(pack.parentFile, "$name.txt")
        val lines = if (optionsFile.isFile) optionsFile.readLines().toMutableList() else mutableListOf()
        val changed = mutableListOf<String>()
        wanted.forEach { (key, off) ->
            val index = lines.indexOfFirst { it.substringBefore('=').trim() == key }
            val now = if (index >= 0) lines[index].substringAfter('=').trim() else null
            if (now == off) return@forEach
            // not in the file means the pack default; only defaults that are already off are fine
            if (now == null && declared[key] == off) return@forEach
            if (index >= 0) lines[index] = "$key=$off" else lines.add("$key=$off")
            changed.add(key)
        }
        if (changed.isNotEmpty()) {
            optionsFile.writeText(lines.joinToString("\n", postfix = "\n"))
            Logger.info(TAG, "$name: ${changed.joinToString()} off (no compute on this engine)")
        }
        changed
    }.onFailure { Logger.warning(TAG, "Could not check the shader pack options", it) }.getOrDefault(emptyList())

    /** NEEDS_COMPUTE options the pack declares, with their default values */
    private fun declaredOptions(pack: File): Map<String, String> {
        val found = mutableMapOf<String, String>()
        val pattern = Regex("""^\s*#define\s+(\w+)\s+(-?\w+)""", RegexOption.MULTILINE)
        fun scan(text: String) {
            pattern.findAll(text).forEach { m ->
                if (m.groupValues[1] in NEEDS_COMPUTE) found.putIfAbsent(m.groupValues[1], m.groupValues[2])
            }
        }
        if (pack.isFile) {
            ZipFile(pack).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".glsl") }.forEach { entry ->
                    scan(zip.getInputStream(entry).use { it.readBytes().decodeToString() })
                }
            }
        } else {
            pack.walkTopDown().filter { it.isFile && it.name.endsWith(".glsl") }.forEach { scan(it.readText()) }
        }
        return found
    }
}
