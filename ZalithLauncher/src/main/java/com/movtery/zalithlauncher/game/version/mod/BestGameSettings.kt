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
import com.movtery.zalithlauncher.utils.device.DeviceTier
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File

/**
 * ONA: the game's own video settings set for high FPS on this phone: vanilla (options.txt),
 * Sodium / Embeddium / Rubidium, OptiFine and the shadow distance of Iris / Oculus.
 *
 * Each settings file is set once (listed in the version folder), so whatever the player changes
 * in game afterwards stays. Files a mod writes on its first start are set on the launch after.
 * Distances are only ever lowered, and only keys the file already has are changed.
 */
object BestGameSettings {
    private const val TAG = "BestGameSettings"
    private const val APPLIED_LIST = "ona_best_settings.txt"

    private val GSON = GsonBuilder().setPrettyPrinting().create()

    private sealed class Target(val relativePath: String, val label: String) {
        /** key:value lines (options.txt, optionsof.txt) */
        class Colon(path: String, label: String, val values: (DeviceTier) -> Map<String, String>) : Target(path, label)
        /** key=value lines (Iris / Oculus properties) */
        class Properties(path: String, label: String, val values: (DeviceTier) -> Map<String, String>) : Target(path, label)
        /** JSON, "a.b" reaches into nested objects */
        class Json(path: String, label: String, val values: (DeviceTier) -> Map<String, Any>) : Target(path, label)
    }

    /** Keys whose current value is only lowered */
    private val LOWER_ONLY = setOf("renderDistance", "simulationDistance", "maxShadowRenderDistance")

    private fun sodiumLike(tier: DeviceTier): Map<String, Any> = mapOf(
        "quality.weather_quality" to "FAST",
        "quality.leaves_quality" to if (tier == DeviceTier.FLAGSHIP) "DEFAULT" else "FAST",
        "quality.enable_vignette" to false,
        "performance.always_defer_chunk_updates" to true,
        "performance.always_defer_chunk_updates_v2" to true,
        "performance.animate_only_visible_textures" to true,
        "performance.use_entity_culling" to true,
        "performance.use_particle_culling" to true,
        "performance.use_fog_occlusion" to true,
        "performance.use_block_face_culling" to true,
        "performance.use_compact_vertex_format" to true
    )

    private val TARGETS = listOf(
        Target.Colon("options.txt", "Minecraft") { tier ->
            mapOf(
                "renderDistance" to when (tier) {
                    DeviceTier.FLAGSHIP -> "12"
                    DeviceTier.HIGH -> "10"
                    DeviceTier.MID -> "8"
                    DeviceTier.LOW -> "6"
                },
                "simulationDistance" to when (tier) {
                    DeviceTier.FLAGSHIP -> "8"
                    DeviceTier.HIGH -> "6"
                    else -> "5"
                },
                "graphicsMode" to "0",
                "fancyGraphics" to "false",
                "renderClouds" to "false",
                "particles" to if (tier == DeviceTier.LOW) "2" else "1",
                "entityShadows" to "false",
                "biomeBlendRadius" to if (tier == DeviceTier.LOW || tier == DeviceTier.MID) "0" else "1",
                "mipmapLevels" to if (tier == DeviceTier.LOW) "0" else "2",
                "ao" to (tier != DeviceTier.LOW).toString(),
                "entityDistanceScaling" to when (tier) {
                    DeviceTier.FLAGSHIP -> "1.0"
                    DeviceTier.HIGH -> "0.75"
                    else -> "0.5"
                },
                "prioritizeChunkUpdates" to "0",
                "menuBackgroundBlurriness" to "0",
                "enableVsync" to "false"
            )
        },
        Target.Json("config/sodium-options.json", "Sodium") { sodiumLike(it) },
        Target.Json("config/embeddium-options.json", "Embeddium") { sodiumLike(it) },
        Target.Json("config/rubidium-options.json", "Rubidium") { sodiumLike(it) },
        Target.Colon("optionsof.txt", "OptiFine") { tier ->
            mapOf(
                "ofFastRender" to "true",
                "ofFastMath" to "true",
                "ofSmartAnimations" to "true",
                "ofRenderRegions" to "true",
                "ofLazyChunkLoading" to "true",
                "ofSmoothFps" to "false",
                "ofChunkUpdates" to "1",
                "ofChunkUpdatesDynamic" to "false",
                "ofAaLevel" to "0",
                "ofAfLevel" to "1",
                "ofClouds" to "3",
                "ofVignette" to "1",
                "ofDynamicLights" to if (tier == DeviceTier.FLAGSHIP) "1" else "3",
                "ofBetterGrass" to "3",
                "ofFogType" to "1",
                "ofTrees" to if (tier == DeviceTier.FLAGSHIP) "0" else "1"
            )
        },
        Target.Properties("config/iris.properties", "Iris") { tier ->
            mapOf("maxShadowRenderDistance" to if (tier == DeviceTier.FLAGSHIP) "16" else "8")
        },
        Target.Properties("config/oculus.properties", "Oculus") { tier ->
            mapOf("maxShadowRenderDistance" to if (tier == DeviceTier.FLAGSHIP) "16" else "8")
        }
    )

    /**
     * Set every settings file of the version that was not set before.
     * @return labels of what was set
     */
    fun apply(version: Version, tier: DeviceTier): List<String> {
        val gameDir = version.getGameDir()
        val listFile = File(version.getVersionPath(), APPLIED_LIST)
        val applied = runCatching { listFile.readLines().map { it.trim() }.toMutableSet() }.getOrDefault(mutableSetOf())
        val labels = mutableListOf<String>()
        TARGETS.forEach { target ->
            if (target.relativePath in applied) return@forEach
            val file = File(gameDir, target.relativePath)
            if (!file.isFile) return@forEach
            runCatching {
                val original = file.readText()
                val updated = when (target) {
                    is Target.Colon -> patchLines(original, target.values(tier), ":")
                    is Target.Properties -> patchLines(original, target.values(tier), "=")
                    is Target.Json -> patchJson(original, target.values(tier))
                }
                if (updated != null && updated != original) {
                    file.writeText(updated)
                    labels.add(target.label)
                }
                applied.add(target.relativePath)
            }.onFailure { Logger.warning(TAG, "Could not set ${target.label}", it) }
        }
        runCatching { listFile.writeText(applied.joinToString("\n")) }
        if (labels.isNotEmpty()) Logger.info(TAG, "Best settings for $tier: $labels")
        return labels
    }

    /** Turned off: forget what was set, so turning it on again sets everything once more */
    fun reset(version: Version) {
        File(version.getVersionPath(), APPLIED_LIST).delete()
    }

    /** Change existing `key<separator>value` lines, keeping the quoting of each value */
    private fun patchLines(text: String, values: Map<String, String>, separator: String): String? {
        val lines = text.lines().toMutableList()
        var changed = false
        values.forEach { (key, wanted) ->
            val index = lines.indexOfFirst { it.trimStart().startsWith("$key$separator") }
            if (index < 0) return@forEach
            val line = lines[index]
            val prefix = line.substring(0, line.indexOf(separator) + 1)
            val current = line.substring(prefix.length).trim()
            val quoted = current.startsWith("\"")
            val bare = current.removeSurrounding("\"")
            // versions before 1.14 keep smooth lighting as a number (ao:2): leave those alone
            if (key == "ao" && bare.toBooleanStrictOrNull() == null) return@forEach
            if (key in LOWER_ONLY) {
                val now = bare.toIntOrNull() ?: return@forEach
                if (now <= (wanted.toIntOrNull() ?: return@forEach)) return@forEach
            }
            val replacement = prefix + if (quoted) "\"$wanted\"" else wanted
            if (replacement != line) {
                lines[index] = replacement
                changed = true
            }
        }
        return if (changed) lines.joinToString("\n") else null
    }

    private fun patchJson(text: String, values: Map<String, Any>): String? {
        val root = JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        var changed = false
        values.forEach { (path, value) ->
            val parts = path.split('.')
            var json: JsonObject = root
            for (part in parts.dropLast(1)) {
                json = json.get(part)?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            }
            val key = parts.last()
            if (!json.has(key)) return@forEach
            val primitive = when (value) {
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
            if (json.get(key) != primitive) {
                json.add(key, primitive)
                changed = true
            }
        }
        return if (changed) GSON.toJson(root) else null
    }
}
