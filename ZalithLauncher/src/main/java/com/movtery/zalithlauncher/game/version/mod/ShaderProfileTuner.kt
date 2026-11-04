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

import com.movtery.zalithlauncher.utils.device.DeviceTier
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File
import java.util.zip.ZipFile

/**
 * ONA: the active shader pack gets the quality profile its own authors made for weaker
 * hardware (BSL, Complementary, Sildur's... define `profile.LOW`, `profile.MEDIUM`... in
 * shaders/shaders.properties). A profile keeps the pack's lighting and colours and lowers what
 * costs the most (shadow resolution, volumetrics, reflections, filtering), so heavy modpacks
 * keep a playable frame rate with shaders on.
 *
 * - Flagship phones get MEDIUM, or LOW when the pack has 60+ mods; high end LOW; weaker
 *   phones VERY LOW. POTATO style profiles (flat lighting) are only a last resort.
 * - Written once per pack into shaderpacks/<pack>.txt, the options file Iris, Oculus and
 *   OptiFine all read; the file is backed up first (ModPerformanceTuner puts it back when
 *   tuning is turned off). Changes the player makes in the game afterwards are kept.
 */
object ShaderProfileTuner {
    private const val TAG = "ShaderProfileTuner"
    const val BACKUP_SUFFIX = ".ona-backup"

    private val VERY_LOW = listOf("VERY_LOW", "VERYLOW", "LOWEST", "MINIMUM", "MINIMAL")
    private val LOW = listOf("LOW", "LITE", "FAST", "PERFORMANCE")
    private val MEDIUM = listOf("MEDIUM", "MID", "BALANCED", "NORMAL")
    private val POTATO = listOf("POTATO", "TOASTER")

    /** @return label for the toast when a profile was written now, null otherwise */
    fun apply(gameDir: File, tier: DeviceTier, modCount: Int): String? {
        val packName = activePack(gameDir) ?: return null
        val packDir = File(gameDir, "shaderpacks")
        val pack = File(packDir, packName).takeIf { it.exists() } ?: return null
        val optionsFile = File(packDir, "$packName.txt")
        val backup = File(optionsFile.path + BACKUP_SUFFIX)
        // Once per pack: the player's own changes win afterwards
        if (backup.exists()) return null

        val properties = readShaderProperties(pack) ?: return null
        val profiles = parseProfiles(properties)
        if (profiles.isEmpty()) return null

        val heavy = modCount >= 60
        val wanted = when (tier) {
            DeviceTier.FLAGSHIP -> if (heavy) listOf(LOW, MEDIUM) else listOf(MEDIUM, LOW)
            DeviceTier.HIGH -> if (heavy) listOf(LOW, VERY_LOW) else listOf(LOW, MEDIUM)
            DeviceTier.MID -> listOf(VERY_LOW, LOW)
            DeviceTier.LOW -> listOf(VERY_LOW, LOW, POTATO)
        } + listOf(LOW, VERY_LOW, POTATO)
        val profileName = wanted.flatten().firstNotNullOfOrNull { name ->
            profiles.keys.firstOrNull { it.equals(name, ignoreCase = true) }
        } ?: return null

        val values = resolve(profileName, profiles, mutableSetOf())
        if (values.isEmpty()) return null

        val original = if (optionsFile.isFile) optionsFile.readText() else ""
        backup.writeText(original)
        optionsFile.writeText(merge(original, values))
        Logger.info(TAG, "$packName: profile $profileName (${values.size} options) for $tier, $modCount mods")
        return "$packName ($profileName)"
    }

    /** Options files this tuner backed up, relative to the game dir, for the restore list */
    fun tunedFiles(gameDir: File): List<String> =
        File(gameDir, "shaderpacks").listFiles { file -> file.name.endsWith(".txt$BACKUP_SUFFIX") }
            .orEmpty()
            .map { "shaderpacks/" + it.name.removeSuffix(BACKUP_SUFFIX) }

    /** The pack Iris / Oculus / OptiFine will load, null when shaders are off */
    private fun activePack(gameDir: File): String? {
        listOf("config/iris.properties", "config/oculus.properties").forEach { path ->
            val props = readKeyValues(File(gameDir, path)) ?: return@forEach
            if (props["enableShaders"]?.trim() == "false") return@forEach
            props["shaderPack"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        readKeyValues(File(gameDir, "optionsshaders.txt"))?.get("shaderPack")?.trim()
            ?.takeIf { it.isNotEmpty() && it != "OFF" && it != "(internal)" }
            ?.let { return it }
        return null
    }

    private fun readKeyValues(file: File): Map<String, String>? {
        if (!file.isFile) return null
        return file.readLines().mapNotNull { line ->
            val index = line.indexOf('=')
            if (line.startsWith("#") || index <= 0) null
            else line.substring(0, index).trim() to line.substring(index + 1)
        }.toMap()
    }

    private fun readShaderProperties(pack: File): String? {
        if (pack.isDirectory) {
            return File(pack, "shaders/shaders.properties").takeIf { it.isFile }?.readText()
        }
        return runCatching {
            ZipFile(pack).use { zip ->
                val entry = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith("shaders/shaders.properties") }
                    .minByOrNull { it.name.length } ?: return null
                zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }.onFailure { Logger.warning(TAG, "Could not read ${pack.name}", it) }.getOrNull()
    }

    /** profile.NAME -> its option tokens, with "\" line continuations joined */
    private fun parseProfiles(text: String): Map<String, List<String>> {
        val joined = text.replace("\r", "").replace(Regex("\\\\\\n[ \\t]*"), " ")
        val result = linkedMapOf<String, List<String>>()
        joined.lines().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("profile.")) return@forEach
            val index = line.indexOf('=')
            if (index <= 0) return@forEach
            val name = line.substring("profile.".length, index).trim()
            val tokens = line.substring(index + 1).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (name.isNotEmpty()) result[name] = tokens
        }
        return result
    }

    /**
     * OptiFine profile syntax: `OPTION` on, `!OPTION` off, `OPTION:value` or `OPTION=value`,
     * `profile.OTHER` includes another profile, `program.*` toggles are left to the pack.
     */
    private fun resolve(name: String, profiles: Map<String, List<String>>, seen: MutableSet<String>): Map<String, String> {
        if (!seen.add(name)) return emptyMap()
        val values = linkedMapOf<String, String>()
        profiles[name].orEmpty().forEach { token ->
            when {
                token.startsWith("profile.") -> values += resolve(token.removePrefix("profile."), profiles, seen)
                token.startsWith("!profile.") || token.contains("program.") -> Unit
                token.startsWith("!") -> values[token.substring(1)] = "false"
                token.contains(':') -> values[token.substringBefore(':')] = token.substringAfter(':')
                token.contains('=') -> values[token.substringBefore('=')] = token.substringAfter('=')
                else -> values[token] = "true"
            }
        }
        return values
    }

    /** The options file with the profile's values set, other lines kept */
    private fun merge(original: String, values: Map<String, String>): String {
        val remaining = values.toMutableMap()
        val lines = original.lines().filter { it.isNotEmpty() }.map { line ->
            val key = line.substringBefore('=').trim()
            remaining.remove(key)?.let { "$key=$it" } ?: line
        }
        return (lines + remaining.map { (key, value) -> "$key=$value" }).joinToString("\n") + "\n"
    }
}
