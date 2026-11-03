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
 * ONA: makes heavy client mods lighter through their own config files, never touching the
 * mods (Sound Physics Remastered, 3D Skin Layers, Entity Texture / Model Features, Dynamic
 * Lights, Subtle Effects, Distant Horizons, Embeddium, Create, Supplementaries, Falling Leaves,
 * LambDynamicLights, Effective, Particle Rain). Keys were checked against the configs these
 * mods write. Every file is backed up before the first change and put back exactly when
 * tuning is turned off.
 *
 * Only keys that already exist in the config are changed, so a mod version with a different
 * config layout is simply left alone. Configs are written by the mods on their first start,
 * so a freshly installed pack is tuned from its second launch.
 */
object ModPerformanceTuner {
    private const val TAG = "ModPerformanceTuner"
    private const val BACKUP_SUFFIX = ".ona-backup"
    private const val TUNED_LIST = "ona_tuned_configs.txt"

    private val GSON = GsonBuilder().setPrettyPrinting().create()

    private sealed class Patch(val relativePath: String, val label: String) {
        /** key = value per line (Java properties) */
        class Properties(path: String, label: String, val values: (DeviceTier) -> Map<String, String>) : Patch(path, label)
        /** key = value, strings quoted (Forge / NeoForge TOML) */
        class Toml(path: String, label: String, val values: (DeviceTier) -> Map<String, String>) : Patch(path, label)
        /** top level JSON keys (Gson configs) */
        class Json(path: String, label: String, val values: (DeviceTier) -> Map<String, Any>) : Patch(path, label)
    }

    private fun strong(tier: DeviceTier) = tier == DeviceTier.FLAGSHIP

    private val PATCHES = listOf(
        // Sound Physics Remastered: ray traced reverb is the heaviest client feature in most packs
        Patch.Properties("config/sound_physics_remastered/soundphysics.properties", "Sound Physics Remastered") { tier ->
            mapOf(
                "environment_evaluation_ray_count" to if (strong(tier)) "16" else "8",
                "environment_evaluation_ray_bounces" to if (strong(tier)) "2" else "1",
                "max_occlusion_rays" to if (strong(tier)) "8" else "4",
                "sound_direction_evaluation" to if (strong(tier)) "true" else "false",
                "render_sound_bounces" to "false",
                "render_occlusion" to "false",
                "performance_logging" to "false",
                "environment_logging" to "false",
                "occlusion_logging" to "false",
                "debug_logging" to "false"
            )
        },
        // 3D Skin Layers: 3D layers only for players close by
        Patch.Json("config/skinlayers.json", "3D Skin Layers") { tier ->
            mapOf<String, Any>(
                "renderDistanceLOD" to when (tier) {
                    DeviceTier.FLAGSHIP -> 8
                    DeviceTier.HIGH -> 6
                    else -> 4
                },
                "fastRender" to true
            )
        },
        // Entity Texture Features: fewer texture checks, emissives only on strong phones
        Patch.Json("config/entity_texture_features.json", "Entity Texture Features") { tier ->
            buildMap<String, Any> {
                put("textureUpdateFrequency_V2", "Slow")
                if (!strong(tier)) {
                    put("enableEmissiveTextures", false)
                    put("enableEmissiveBlockEntities", false)
                    put("enableBlinking", false)
                }
            }
        },
        // Dynamic lights: update less often
        Patch.Toml("config/sodiumdynamiclights-client.toml", "Dynamic Lights") { tier ->
            buildMap<String, String> {
                put("mode", if (strong(tier)) "FAST" else "SLOW")
                if (!strong(tier)) put("block_entities", "false")
            }
        },
        // Subtle Effects: cull its particles closer
        Patch.Toml("config/subtle_effects/general.toml", "Subtle Effects") { tier ->
            mapOf(
                "enableParticleCulling" to "true",
                "particleRenderDistance" to if (strong(tier)) "16" else "12",
                "cullParticlesInUnloadedChunks" to "true"
            )
        },
        // Distant Horizons: the LOD radius and quality decide most of its GPU, RAM and CPU cost
        Patch.Toml("config/DistantHorizons.toml", "Distant Horizons") { tier ->
            mapOf(
                "lodChunkRenderDistanceRadius" to when (tier) {
                    DeviceTier.FLAGSHIP -> "96"
                    DeviceTier.HIGH -> "64"
                    else -> "32"
                },
                "horizontalQuality" to "LOW",
                "verticalQuality" to "LOW",
                "maxHorizontalResolution" to if (strong(tier)) "TWO_BLOCKS" else "FOUR_BLOCKS",
                "transparency" to "FAKE",
                "enableSsao" to "false",
                "enableNoiseTexture" to "false",
                // Leave the big cores to the game itself
                "numberOfThreads" to if (strong(tier)) "3" else "2"
            )
        },
        // Embeddium (Sodium for Forge): every culling path on, chunk updates deferred
        Patch.Json("config/embeddium-options.json", "Embeddium") { tier ->
            buildMap<String, Any> {
                put("performance.always_defer_chunk_updates", true)
                put("performance.animate_only_visible_textures", true)
                put("performance.use_entity_culling", true)
                put("performance.use_particle_culling", true)
                put("performance.use_fog_occlusion", true)
                put("performance.use_block_face_culling", true)
                put("performance.use_compact_vertex_format", true)
                if (!strong(tier)) {
                    put("quality.weather_quality", "FAST")
                    put("quality.leaves_quality", "FAST")
                }
            }
        },
        // Entity Model Features: full animations only close to the camera
        Patch.Json("config/entity_model_features.json", "Entity Model Features") { tier ->
            mapOf<String, Any>(
                "animationLODDistance" to if (strong(tier)) 12 else 8,
                "retainDetailOnLowFps" to false
            )
        },
        // Create: fan particles and filter item rendering
        Patch.Toml("config/create-client.toml", "Create") { tier ->
            mapOf(
                "fanParticleDensity" to if (strong(tier)) "0.25" else "0.1",
                "filterItemRenderDistance" to "6.0"
            )
        },
        // Supplementaries: rotation particles, fancy block renderers below flagship
        Patch.Toml("config/supplementaries-client.toml", "Supplementaries") { tier ->
            buildMap<String, String> {
                put("turn_particles", "false")
                if (!strong(tier)) put("fancy_renderers", "false")
            }
        },
        // Falling Leaves: fewer, shorter living leaf particles
        Patch.Json("config/fallingleaves.json", "Falling Leaves") { tier ->
            mapOf<String, Any>(
                "leafSpawnRate" to if (strong(tier)) 4 else 2,
                "coniferLeafSpawnRate" to if (strong(tier)) 2 else 1,
                "leafLifespan" to 120
            )
        },
        // LambDynamicLights (Fabric / Connector)
        Patch.Toml("config/lambdynlights.toml", "LambDynamicLights") { tier ->
            mapOf("mode" to if (strong(tier)) "fast" else "fastest")
        },
        // Effective: water splashes, cascades and ambient particles
        Patch.Json("config/effective.json", "Effective") { tier ->
            mapOf<String, Any>(
                "flowingWaterSplashingDensity" to if (strong(tier)) 25 else 10,
                "cascadeCloudDensity" to if (strong(tier)) 1.0 else 0.5,
                "cascadeMistDensity" to 0.5,
                "rainRippleDensity" to 2,
                "fireflyDensity" to 0.5,
                "sculkDustDensity" to 50.0
            )
        },
        // Particle Rain: weather particle budget
        Patch.Json("config/particlerain.json", "Particle Rain") { tier ->
            mapOf<String, Any>(
                "perf.maxParticleAmount" to if (strong(tier)) 800 else 400,
                "perf.particleDensity" to if (strong(tier)) 60 else 40,
                "perf.particleStormDensity" to if (strong(tier)) 120 else 80,
                "perf.particleDistance" to 12
            )
        }
    )

    /**
     * Tune every heavy mod config found in the version.
     * @return labels of the mods that were tuned
     */
    fun apply(version: Version, tier: DeviceTier): List<String> {
        val gameDir = version.getGameDir()
        val tuned = mutableListOf<String>()
        val touched = mutableSetOf<String>()
        PATCHES.forEach { patch ->
            val file = File(gameDir, patch.relativePath)
            if (!file.isFile) return@forEach
            runCatching {
                val original = file.readText()
                val updated = when (patch) {
                    is Patch.Properties -> patchLines(original, patch.values(tier), quoteStrings = false, separator = "=")
                    is Patch.Toml -> patchLines(original, patch.values(tier), quoteStrings = true, separator = "=")
                    is Patch.Json -> patchJson(original, patch.values(tier))
                }
                if (updated != null && updated != original) {
                    val backup = File(file.path + BACKUP_SUFFIX)
                    if (!backup.exists()) file.copyTo(backup)
                    file.writeText(updated)
                    tuned.add(patch.label)
                }
                if (File(file.path + BACKUP_SUFFIX).exists()) touched.add(patch.relativePath)
            }.onFailure { Logger.warning(TAG, "Could not tune ${patch.label}", it) }
        }
        if (touched.isNotEmpty()) {
            runCatching { File(version.getVersionPath(), TUNED_LIST).writeText(touched.joinToString("\n")) }
        }
        if (tuned.isNotEmpty()) Logger.info(TAG, "Tuned for $tier: $tuned")
        return tuned
    }

    /** Put every tuned config back exactly as it was. @return how many files were restored */
    fun restore(version: Version): Int {
        val list = File(version.getVersionPath(), TUNED_LIST)
        if (!list.isFile) return 0
        val gameDir = version.getGameDir()
        var restored = 0
        list.readLines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { relative ->
            val file = File(gameDir, relative)
            val backup = File(file.path + BACKUP_SUFFIX)
            if (backup.isFile && runCatching { backup.copyTo(file, overwrite = true); backup.delete() }.isSuccess) restored++
        }
        list.delete()
        if (restored > 0) Logger.info(TAG, "Restored $restored config(s) of ${version.getVersionName()}")
        return restored
    }

    /**
     * Change existing `key = value` lines, keeping comments, indentation, sections and the
     * quoting of each value.
     */
    private fun patchLines(text: String, values: Map<String, String>, quoteStrings: Boolean, separator: String): String? {
        var result = text
        var changed = false
        values.forEach { (key, value) ->
            val regex = Regex("(?m)^([ \\t]*${Regex.escape(key)}[ \\t]*${Regex.escape(separator)}[ \\t]*)(\"?)([^\"\\r\\n#]*)(\"?)")
            val match = regex.find(result) ?: return@forEach
            val quoted = match.groupValues[2] == "\"" || (quoteStrings && value.toBooleanStrictOrNull() == null && value.toDoubleOrNull() == null)
            val replacement = match.groupValues[1] + if (quoted) "\"$value\"" else value
            if (match.value != replacement) {
                result = result.replaceRange(match.range, replacement)
                changed = true
            }
        }
        return if (changed) result else null
    }

    /** Change existing keys of a JSON config, "a.b" reaches into nested objects */
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
