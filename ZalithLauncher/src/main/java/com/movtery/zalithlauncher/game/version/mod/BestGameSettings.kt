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
    /**
     * v3: Minecraft 26's own heavy options (order independent transparency, cutout leaves, RGSS
     * texture filtering, chunk fade-in, vignette, weather radius); every file is set once more
     */
    private const val APPLIED_LIST = "ona_best_settings_v3.txt"

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
    private val LOWER_ONLY = setOf(
        "renderDistance", "simulationDistance", "maxShadowRenderDistance",
        "shadowMapResolution", "shadowDistance", "shadowResMul", "mipmapLevels", "weatherRadius"
    )

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
        "performance.use_compact_vertex_format" to true,
        "quality.cloud_quality" to "FAST",
        "advanced.enable_memory_tracing" to false
    )

    /**
     * Minecraft 26.x video options (keys checked in the 26.3 client's Options / GraphicsPreset):
     *  - improvedTransparency: order independent transparency draws every translucent surface in
     *    several extra passes (depth bounds, transmittance, accumulate, composite): the heaviest
     *    switch on a phone GPU, off below flagships.
     *  - cutoutLeaves: see-through leaves are alpha tested, which turns off the early depth test of
     *    tile GPUs (Mali, Adreno, PowerVR); opaque leaves are much faster in forests.
     *  - textureFiltering: 1 (RGSS) samples every texture four times per pixel, 0 = plain.
     *  - chunkSectionFadeInTime 0: new chunks appear at once instead of blending in.
     *  - vignette off, weatherRadius lower (only lowered).
     * Changing single options makes the preset "custom", as the game itself does.
     * Keys a version does not have are skipped (only keys already in options.txt are changed).
     */
    private fun minecraft26(tier: DeviceTier): Map<String, String> {
        val flagship = tier == DeviceTier.FLAGSHIP
        return buildMap {
            put("graphicsPreset", "custom")
            put("vignette", "false")
            put("chunkSectionFadeInTime", if (flagship) "0.25" else "0")
            put("weatherRadius", when (tier) {
                DeviceTier.FLAGSHIP -> "10"
                DeviceTier.HIGH -> "7"
                else -> "5"
            })
            if (!flagship) {
                put("improvedTransparency", "false")
                put("cutoutLeaves", "false")
                put("textureFiltering", "0")
            }
        }
    }

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
                "enableVsync" to "false",
                // 1.12 and older: vertex buffers and framebuffers on, the fast path on GL4ES
                "useVbo" to "true",
                "fboEnable" to "true"
            ) + minecraft26(tier)
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
        },
        // OptiFine shaders: smaller shadow map, no shader anti aliasing
        Target.Properties("optionsshaders.txt", "OptiFine shaders") { tier ->
            mapOf(
                "shadowResMul" to if (tier == DeviceTier.FLAGSHIP) "0.5" else "0.25",
                "antialiasingLevel" to "0"
            )
        }
    )

    /**
     * Options of the selected shader pack (shaderpacks/<pack>.txt, where Iris, Oculus and
     * OptiFine keep the pack settings): the shadow map and the shadow distance are the biggest
     * cost of nearly every pack. A pack without such an option simply ignores it.
     */
    private fun shaderPackValues(tier: DeviceTier): Map<String, String> = mapOf(
        "shadowMapResolution" to if (tier == DeviceTier.FLAGSHIP || tier == DeviceTier.HIGH) "1024" else "512",
        "shadowDistance" to if (tier == DeviceTier.FLAGSHIP || tier == DeviceTier.HIGH) "96" else "64"
    )

    /** The pack picked in Iris / Oculus / OptiFine, or null */
    private fun selectedShaderPack(gameDir: File): String? {
        val sources = listOf(
            File(gameDir, "config/iris.properties"),
            File(gameDir, "config/oculus.properties"),
            File(gameDir, "optionsshaders.txt")
        )
        for (source in sources) {
            if (!source.isFile) continue
            val line = runCatching { source.readLines() }.getOrDefault(emptyList())
                .firstOrNull { it.trimStart().startsWith("shaderPack=") } ?: continue
            // Java properties escape ':' '=' and spaces with a backslash
            val pack = line.substringAfter('=').trim().replace(Regex("""\\(.)"""), "$1")
            if (pack.isNotEmpty() && pack != "OFF" && pack != "(internal)") return pack
        }
        return null
    }

    /** @return the pack name when its options were changed */
    private fun applyShaderPack(gameDir: File, tier: DeviceTier, applied: MutableSet<String>): String? {
        val pack = selectedShaderPack(gameDir) ?: return null
        // ONA's own pack gets its level from ONA Graphics
        if (pack == com.movtery.zalithlauncher.game.graphics.OnaGraphics.SHADER_NAME) return null
        val relative = "shaderpacks/$pack.txt"
        if (relative in applied) return null
        if (!File(gameDir, "shaderpacks/$pack").exists()) return null
        val file = File(gameDir, relative)
        return runCatching {
            val original = if (file.isFile) file.readText() else ""
            val values = shaderPackValues(tier)
            // keys the file does not have yet are added, the others only lowered
            val missing = values.filterKeys { key -> original.lines().none { it.trimStart().startsWith("$key=") } }
            val patched = patchLines(original, values - missing.keys, "=") ?: original
            val lines = patched.lines().filter { it.isNotBlank() } + missing.map { (key, value) -> "$key=$value" }
            val updated = lines.joinToString("\n", postfix = "\n")
            applied.add(relative)
            if (updated.trim() != original.trim()) {
                file.writeText(updated)
                pack
            } else null
        }.onFailure { Logger.warning(TAG, "Could not set the shader pack $pack", it) }.getOrNull()
    }

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
        applyShaderPack(gameDir, tier, applied)?.let { labels.add(it) }
        runCatching { listFile.writeText(applied.joinToString("\n")) }
        if (labels.isNotEmpty()) Logger.info(TAG, "Best settings for $tier: $labels")
        return labels
    }

    /** Turned off: forget what was set, so turning it on again sets everything once more */
    fun reset(version: Version) {
        File(version.getVersionPath(), APPLIED_LIST).delete()
        File(version.getVersionPath(), "ona_best_settings.txt").delete()
        File(version.getVersionPath(), "ona_best_settings_v2.txt").delete()
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
                val now = bare.toDoubleOrNull() ?: return@forEach
                if (now <= (wanted.toDoubleOrNull() ?: return@forEach)) return@forEach
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
