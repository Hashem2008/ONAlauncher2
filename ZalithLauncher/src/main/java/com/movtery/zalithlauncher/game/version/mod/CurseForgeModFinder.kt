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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.BuildKeys
import com.movtery.zalithlauncher.game.download.assets.platform.curseforge.CURSEFORGE_API
import com.movtery.zalithlauncher.game.download.assets.platform.curseforge.MCIM_CURSEFORGE_API
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ONA: second source for missing mod requirements. Many Forge packs (DeceasedCraft: Apocalypse
 * Now, Zombie Extreme, Nameless Trinkets, FTB Quests...) need mods that only live on CurseForge,
 * so when Modrinth has nothing the requirement is looked up there. Every candidate jar is still
 * checked by its own metadata ([ModCompat.downloadIfCompatible] with the mod id it must provide),
 * so a wrong search hit is never kept.
 */
object CurseForgeModFinder {
    private const val TAG = "CurseForgeModFinder"
    private const val GAME_ID_MINECRAFT = 432
    private const val CLASS_ID_MODS = 6
    private const val MAX_PROJECTS = 4
    private const val MAX_FILES = 3

    private fun apiKey(): String =
        AllSettings.curseForgeApiKey.getValue().ifBlank { BuildKeys.CURSEFORGE_API }

    /** The official API needs a key, the MCIM mirror serves the same data without one */
    private fun sources(): List<String> =
        if (apiKey().isNotBlank()) listOf(CURSEFORGE_API, MCIM_CURSEFORGE_API) else listOf(MCIM_CURSEFORGE_API)

    private fun loaderType(loader: String?): Int? = when (loader) {
        "forge" -> 1
        "fabric" -> 4
        "quilt" -> 5
        "neoforge" -> 6
        else -> null
    }

    /**
     * Install a build of the mod that provides [modId] for this environment.
     * @return the installed jar, or null when CurseForge has none that fits
     */
    fun install(modId: String, modsDir: File, environment: ModCompat.Environment): File? {
        val loader = loaderType(environment.loader) ?: return null
        for (base in sources()) {
            val found = runCatching { installFrom(base, modId, modsDir, environment, loader) }
                .onFailure { Logger.warning(TAG, "$modId via $base failed: ${it.message}") }
                .getOrNull()
            if (found != null) {
                Logger.info(TAG, "$modId installed from CurseForge: ${found.name}")
                return found
            }
        }
        return null
    }

    private fun installFrom(base: String, modId: String, modsDir: File, environment: ModCompat.Environment, loader: Int): File? {
        val tried = mutableSetOf<Long>()
        for (query in queries(modId)) {
            for (projectId in search(base, query, environment.gameVersion, loader)) {
                if (!tried.add(projectId) || tried.size > MAX_PROJECTS) continue
                for (file in files(base, projectId, environment.gameVersion, loader)) {
                    val installed = ModCompat.downloadIfCompatible(file.first, file.second, modsDir, environment, modId)
                    if (installed != null) return installed
                }
            }
            if (tried.size >= MAX_PROJECTS) break
        }
        return null
    }

    /** "legendarysurvivaloverhaul", "nameless_trinkets" -> "nameless trinkets", "nameless-trinkets" */
    private fun queries(modId: String): List<String> = linkedSetOf(
        "slug:" + modId.replace('_', '-'),
        modId.replace('_', ' ').replace('-', ' '),
        modId
    ).toList()

    private fun search(base: String, query: String, gameVersion: String, loader: Int): List<Long> {
        val filter = if (query.startsWith("slug:")) "&slug=" + encode(query.removePrefix("slug:"))
        else "&searchFilter=" + encode(query) + "&sortField=2&sortOrder=desc"
        val url = "$base/mods/search?gameId=$GAME_ID_MINECRAFT&classId=$CLASS_ID_MODS&pageSize=5" +
                "&gameVersion=" + encode(gameVersion) + "&modLoaderType=$loader" + filter
        val data = get(url).getAsJsonArray("data") ?: return emptyList()
        return data.mapNotNull { runCatching { it.asJsonObject.get("id").asLong }.getOrNull() }
    }

    /** (download url, file name) of the newest files for this game version and loader */
    private fun files(base: String, projectId: Long, gameVersion: String, loader: Int): List<Pair<String, String>> {
        val url = "$base/mods/$projectId/files?pageSize=$MAX_FILES&gameVersion=" + encode(gameVersion) +
                "&modLoaderType=$loader"
        val data = get(url).getAsJsonArray("data") ?: return emptyList()
        return data.mapNotNull { element ->
            val file = element.asJsonObject
            val id = file.get("id")?.asLong ?: return@mapNotNull null
            val name = file.get("fileName")?.asString ?: return@mapNotNull null
            if (!name.endsWith(".jar")) return@mapNotNull null
            // Mods that turned off third party downloads have no downloadUrl, the CDN still serves them
            val download = file.get("downloadUrl")?.takeIf { !it.isJsonNull }?.asString
                ?: "https://edge.forgecdn.net/files/${id / 1000}/${id % 1000}/${encode(name).replace("+", "%20")}"
            download to name
        }.take(MAX_FILES)
    }

    private fun get(url: String): JsonObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "ONALauncher2")
        if (url.startsWith(CURSEFORGE_API)) connection.setRequestProperty("x-api-key", apiKey())
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return connection.inputStream.use { JsonParser.parseReader(it.reader()).asJsonObject }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
