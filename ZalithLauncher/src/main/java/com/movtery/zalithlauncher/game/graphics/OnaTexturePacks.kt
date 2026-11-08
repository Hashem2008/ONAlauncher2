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

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.mod.ModCompat
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ONA Graphics textures: realistic resource packs from Modrinth by resolution (16x ... 512x),
 * the same files the Modrinth site gives. ONA Mobile Shaders reads their normal / specular maps
 * when they have them (labPBR), else ONA Mobile PBR's.
 */
object OnaTexturePacks {
    private const val TAG = "OnaTexturePacks"
    private const val API = "https://api.modrinth.com/v2"
    private const val USER_AGENT = "ONALauncher2 (github.com/Hashem2008/ONAlauncher2)"

    val RESOLUTIONS = listOf("16x", "32x", "64x", "128x", "256x", "512x+")
    private val RESOLUTION_TAGS = setOf("8x-", "16x", "32x", "48x", "64x", "128x", "256x", "512x+")

    /** Add-ons that carry a resolution tag but do not replace the block textures */
    private val NOT_TEXTURES = listOf(
        "animation", "sky", "grass", "gui", "-ui", "font", "sound", "chat", "panorama", "armor",
        "sun", "moon", "3d", "emissive", "overlay", "cape", "particle", "menu", "hud", "icon", "entity",
        "mace", "capes", "music", "dark-mode", "minecart"
    )

    class Pack(val slug: String, val title: String, val downloads: Long, val pbr: Boolean)

    /** Realistic texture packs of one resolution, PBR ones first */
    suspend fun search(resolution: String): List<Pack> = withContext(Dispatchers.IO) {
        val facets = "[[\"project_type:resourcepack\"],[\"categories:$resolution\"],[\"categories:realistic\",\"categories:pbr\"]]"
        val url = "$API/search?facets=${encode(facets)}&index=downloads&limit=60"
        val hits = runCatching { JsonParser.parseString(get(url)).asJsonObject.getAsJsonArray("hits") }
            .onFailure { Logger.warning(TAG, "Search failed", it) }
            .getOrNull() ?: return@withContext emptyList()
        hits.mapNotNull { element ->
            val hit = element.asJsonObject
            val slug = hit.get("slug")?.asString ?: return@mapNotNull null
            val categories = (hit.getAsJsonArray("categories").orEmpty() + hit.getAsJsonArray("display_categories").orEmpty()).toSet()
            // a full texture pack is made in one or two resolutions, add-ons claim them all
            if (categories.count { it in RESOLUTION_TAGS } > 2) return@mapNotNull null
            if (NOT_TEXTURES.any { slug.contains(it) }) return@mapNotNull null
            Pack(slug, hit.get("title")?.asString ?: slug, hit.get("downloads")?.asLong ?: 0L, "pbr" in categories)
        }.sortedWith(compareByDescending<Pack> { it.pbr }.thenByDescending { it.downloads }).take(8)
    }

    /**
     * Downloads the pack's file for this game version (else its newest) into the version's
     * resourcepacks folder and puts it on top of the enabled packs.
     * @return the file name, or null
     */
    suspend fun install(version: Version, pack: Pack): String? = withContext(Dispatchers.IO) {
        runCatching {
            val mc = version.getVersionInfo()?.minecraftVersion
            val versions = (mc?.let { versionsOf(pack.slug, it) }?.takeIf { it.size() > 0 } ?: versionsOf(pack.slug, null))
            val file = versions.firstNotNullOfOrNull { element ->
                ModCompat.primaryFile(element.asJsonObject)?.takeIf { it.get("filename").asString.endsWith(".zip") }
            } ?: return@runCatching null
            val name = File(file.get("filename").asString).name
            val dir = File(version.getGameDir(), "resourcepacks").apply { mkdirs() }
            val target = File(dir, name)
            if (!target.isFile) ModCompat.download(file.get("url").asString, target)
            enableOnTop(version.getGameDir(), "file/$name")
            Logger.info(TAG, "${pack.slug}: $name enabled for ${version.getVersionName()}")
            name
        }.onFailure { Logger.warning(TAG, "Could not install ${pack.slug}", it) }.getOrNull()
    }

    private fun versionsOf(slug: String, mc: String?): JsonArray {
        val url = "$API/project/$slug/version" + (mc?.let { "?game_versions=${encode("[\"$it\"]")}" } ?: "")
        return JsonParser.parseString(get(url)).asJsonArray
    }

    /** options.txt: takes [id] out of resourcePacks (the file stays) */
    internal fun removePack(gameDir: File, id: String) {
        val options = File(gameDir, "options.txt")
        if (!options.isFile) return
        val lines = options.readLines().toMutableList()
        val index = lines.indexOfFirst { it.startsWith("resourcePacks:") }
        if (index < 0) return
        val list = runCatching { JsonParser.parseString(lines[index].substringAfter(':')).asJsonArray }.getOrNull() ?: return
        val kept = JsonArray()
        list.forEach { if (it.asString != id) kept.add(it) }
        if (kept.size() == list.size()) return
        lines[index] = "resourcePacks:$kept"
        options.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    /** options.txt: [id] just above the game's own textures, so every other pack still wins */
    internal fun enableAboveVanilla(gameDir: File, id: String) {
        val options = File(gameDir, "options.txt")
        val lines = if (options.isFile) options.readLines().toMutableList() else mutableListOf()
        val index = lines.indexOfFirst { it.startsWith("resourcePacks:") }
        val list = runCatching { JsonParser.parseString(lines[index].substringAfter(':')).asJsonArray }.getOrNull()
            ?: JsonArray().apply { add("vanilla") }
        val ids = list.mapNotNull { runCatching { it.asString }.getOrNull() }.filter { it != id }.toMutableList()
        if ("vanilla" !in ids) ids.add(0, "vanilla")
        // after the built-in packs (vanilla, fabric, mod resources), before the first file pack
        val firstFile = ids.indexOfFirst { it.startsWith("file/") }.let { if (it < 0) ids.size else it }
        ids.add(maxOf(firstFile, ids.indexOf("vanilla") + 1), id)
        val out = JsonArray().apply { ids.forEach { add(it) } }
        val line = "resourcePacks:$out"
        if (index >= 0) lines[index] = line else lines.add(line)
        options.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    /** options.txt: the pack last in resourcePacks (highest priority), out of the incompatible list */
    private fun enableOnTop(gameDir: File, id: String) {
        val options = File(gameDir, "options.txt")
        val lines = if (options.isFile) options.readLines().toMutableList() else mutableListOf()
        val index = lines.indexOfFirst { it.startsWith("resourcePacks:") }
        val list = runCatching { JsonParser.parseString(lines[index].substringAfter(':')).asJsonArray }.getOrNull()
            ?: JsonArray().apply { add("vanilla") }
        val kept = JsonArray()
        list.forEach { if (it.asString != id) kept.add(it) }
        if (kept.size() == 0) kept.add("vanilla")
        kept.add(id)
        val line = "resourcePacks:$kept"
        if (index >= 0) lines[index] = line else lines.add(line)
        val incompatible = lines.indexOfFirst { it.startsWith("incompatibleResourcePacks:") }
        if (incompatible >= 0) {
            runCatching {
                val old = JsonParser.parseString(lines[incompatible].substringAfter(':')).asJsonArray
                val cleaned = JsonArray()
                old.forEach { if (it.asString != id) cleaned.add(it) }
                lines[incompatible] = "incompatibleResourcePacks:$cleaned"
            }
        }
        options.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun JsonArray?.orEmpty(): List<String> = this?.mapNotNull { runCatching { it.asString }.getOrNull() } ?: emptyList()

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        return try {
            if (connection.responseCode !in 200..299) throw java.io.IOException("HTTP ${connection.responseCode}")
            connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }
}
