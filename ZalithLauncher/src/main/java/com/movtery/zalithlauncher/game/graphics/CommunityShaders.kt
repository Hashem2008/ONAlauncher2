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

import android.content.Context
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.mod.ModCompat
import com.movtery.zalithlauncher.utils.device.DeviceTier
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipFile

/**
 * Shader Community (launcher setting): the popular shader packs, downloaded unchanged from Modrinth
 * (their authors' own files: most of them are All Rights Reserved, so ONA does not ship edited
 * copies) and set up for phones through their own options: the profile that fits the device and
 * lighter shadows, written to the pack's options file like a player would in the shader menu.
 * Used instead of ONA Mobile Shaders while one is chosen.
 */
object CommunityShaders {
    private const val TAG = "CommunityShaders"
    private const val API = "https://api.modrinth.com/v2"
    private const val USER_AGENT = "ONALauncher2 (github.com/Hashem2008/ONAlauncher2)"
    private const val STAMP = "ona_community.txt"
    /** Next to a pack this launcher set up, so the heavy-mod tuner leaves its profile alone */
    const val MARKER = ".ona_community"

    /** Modrinth slug to the name shown in the settings */
    val SHADERS: Map<String, String> = linkedMapOf(
        "complementary-reimagined" to "Complementary Reimagined",
        "complementary-unbound" to "Complementary Unbound",
        "bsl-shaders" to "BSL",
        "photon-shader" to "Photon",
        "bliss-shader" to "Bliss",
        "solas-shader" to "Solas",
        "nostalgia-shader" to "Nostalgia",
        "makeup-ultra-fast-shaders" to "MakeUp Ultra Fast",
        "sildurs-vibrant-shaders" to "Sildur's Vibrant"
    )
    val CHOICES: List<String> = listOf("OFF") + SHADERS.keys

    /** Profile names that fit each device class, best guess first (packs name them differently) */
    private val PROFILE_NAMES = mapOf(
        DeviceTier.LOW to listOf("POTATO", "VERY_LOW", "VERYLOW", "LOWEST", "MINIMUM", "MIN", "LITE", "LOW", "FAST"),
        DeviceTier.MID to listOf("VERYLOW", "VERY_LOW", "MINIMUM", "LOW", "LITE", "FAST"),
        DeviceTier.HIGH to listOf("LOW", "LITE", "MEDIUM", "MED"),
        DeviceTier.FLAGSHIP to listOf("MEDIUM", "MED", "MID", "DEFAULT", "HIGH")
    )

    /**
     * Sets up [choice] for [version] when it changed since the last launch, else only keeps it
     * selected. "OFF" switches a community pack off again (ONA Graphics then does its own part).
     * @return a line for a toast, or null when nothing was done
     */
    suspend fun applyOnLaunch(context: Context, version: Version, choice: String, tier: DeviceTier): String? = withContext(Dispatchers.IO) {
        val gameDir = version.getGameDir()
        val stampFile = File(version.getVersionPath(), STAMP)
        val stamp = runCatching { stampFile.readText().trim() }.getOrDefault("")
        if (choice == "OFF" || choice !in SHADERS) {
            if (stamp.isEmpty()) return@withContext null
            stampFile.delete()
            OnaGraphics.setShaderEnabled(gameDir, false)
            return@withContext context.getString(R.string.ona_community_turned_off)
        }
        val title = SHADERS.getValue(choice)
        val parts = stamp.split('|')
        val packFile = parts.getOrNull(1)?.let { File(File(gameDir, "shaderpacks"), it) }
        val loaderNow = OnaGraphics.detectLoader(version)
        if (parts.getOrNull(0) == choice && parts.getOrNull(2) == tier.name && packFile?.isFile == true &&
            loaderNow != OnaGraphics.Loader.NONE
        ) {
            OnaGraphics.selectShader(gameDir, loaderNow, packFile.name)
            return@withContext null
        }

        val lines = mutableListOf<String>()
        val loader = OnaGraphics.ensureShaderLoader(context, version, lines) { }
            ?: return@withContext (lines + context.getString(R.string.ona_community_failed, title)).joinToString("\n")
        val result = runCatching {
            val mc = version.getVersionInfo()?.minecraftVersion
            val got = download(choice, gameDir, loader, mc) ?: return@runCatching null
            val pack = got.file
            got.madeFor?.let { lines.add(context.getString(R.string.ona_community_not_made_for, title, it, mc)) }
            val (profile, options) = phoneOptions(pack, tier)
            File(pack.parentFile, "${pack.name}.txt").writeText(
                options.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" }
            )
            File(pack.parentFile, "${pack.name}$MARKER").writeText(tier.name)
            OnaGraphics.selectShader(gameDir, loader, pack.name)
            stampFile.writeText("$choice|${pack.name}|${tier.name}")
            Logger.info(TAG, "${version.getVersionName()}: $title (${pack.name}), profile ${profile ?: "default"}, ${options.size} options")
            context.getString(R.string.ona_community_on, title, profile ?: "default")
        }.onFailure { Logger.warning(TAG, "Could not set up $choice", it) }.getOrNull()
        (lines + (result ?: context.getString(R.string.ona_community_failed, title))).joinToString("\n")
    }

    /** The newest file of the pack for this loader, into shaderpacks (kept when already there) */
    /** The pack file, and the newest game version it is made for when that is not [mc] (else null) */
    private class Download(val file: File, val madeFor: String?)

    /**
     * The newest file of the pack for this loader made for this game version, else its newest file
     * (Nostalgia 5.1 is made up to 1.21.4: on 26.x parts of it look wrong, the player is told)
     */
    private fun download(slug: String, gameDir: File, loader: OnaGraphics.Loader, mc: String?): Download? {
        val loaders = if (loader == OnaGraphics.Loader.OPTIFINE) "[\"optifine\"]" else "[\"iris\"]"
        val url = "$API/project/$slug/version?loaders=${URLEncoder.encode(loaders, "UTF-8")}"
        val versions = JsonParser.parseString(get(url)).asJsonArray.map { it.asJsonObject }
        fun gameVersions(v: com.google.gson.JsonObject) =
            runCatching { v.getAsJsonArray("game_versions").map { it.asString } }.getOrDefault(emptyList())
        val ordered = if (mc == null) versions else versions.filter { mc in gameVersions(it) } + versions.filter { mc !in gameVersions(it) }
        val chosen = ordered.firstOrNull { v ->
            ModCompat.primaryFile(v)?.get("filename")?.asString?.endsWith(".zip") == true
        } ?: return null
        val file = ModCompat.primaryFile(chosen)
        val dir = File(gameDir, "shaderpacks").apply { mkdirs() }
        val target = File(dir, File(file.get("filename").asString).name)
        if (!target.isFile) ModCompat.download(file.get("url").asString, target)
        val made = gameVersions(chosen)
        return Download(target, if (mc != null && mc !in made) made.lastOrNull() else null)
    }

    /**
     * The pack's own profile that fits [tier] (its options resolved, profiles inside profiles
     * included), with the shadow numbers a phone can carry.
     * @return the profile name (null when the pack has none) and the options to write
     */
    private fun phoneOptions(pack: File, tier: DeviceTier): Pair<String?, Map<String, String>> {
        var declaresShadows = false
        val properties = ZipFile(pack).use { zip ->
            val entries = zip.entries().toList()
            // the shadow numbers are options of the pack when its code declares them
            declaresShadows = entries.any { entry ->
                (entry.name.endsWith(".glsl") || entry.name.endsWith(".fsh") || entry.name.endsWith(".vsh")) &&
                    zip.getInputStream(entry).use { it.readBytes().decodeToString() }.contains("shadowMapResolution")
            }
            val entry = entries.firstOrNull { it.name.endsWith("shaders/shaders.properties") && !it.name.contains("/world") }
            entry?.let { e -> zip.getInputStream(e).use { it.readBytes().decodeToString() } } ?: ""
        }
        // line continuations ("\" at the end) joined
        val text = properties.replace(Regex("\\\\\\r?\\n"), " ")
        val profiles = linkedMapOf<String, String>()
        Regex("^\\s*profile\\.([A-Za-z0-9_]+)\\s*=(.*)$", RegexOption.MULTILINE).findAll(text).forEach {
            profiles[it.groupValues[1]] = it.groupValues[2].trim()
        }
        val wanted = PROFILE_NAMES.getValue(tier)
        // only real quality profiles (Bliss uses profiles for other things): else the pack's defaults
        val name = wanted.firstNotNullOfOrNull { want -> profiles.keys.firstOrNull { it.equals(want, ignoreCase = true) } }
        val options = linkedMapOf<String, String>()
        fun resolve(profile: String, depth: Int) {
            if (depth > 8) return
            val line = profiles[profile] ?: return
            line.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { token ->
                when {
                    token.startsWith("profile.") -> resolve(token.removePrefix("profile."), depth + 1)
                    token.contains('=') -> options[token.substringBefore('=')] = token.substringAfter('=')
                    token.startsWith("!") -> options[token.substring(1)] = "false"
                    else -> options[token] = "true"
                }
            }
        }
        if (name != null) resolve(name, 0)
        val maxResolution = if (tier == DeviceTier.FLAGSHIP) 2048 else 1024
        val maxDistance = if (tier == DeviceTier.FLAGSHIP) 128f else 96f
        options["shadowMapResolution"]?.toIntOrNull()?.let { if (it > maxResolution) options["shadowMapResolution"] = maxResolution.toString() }
        options["shadowDistance"]?.toFloatOrNull()?.let { if (it > maxDistance) options["shadowDistance"] = maxDistance.toString() }
        if (declaresShadows) {
            options.putIfAbsent("shadowMapResolution", maxResolution.toString())
            options.putIfAbsent("shadowDistance", maxDistance.toString())
        }
        return Pair(name, options)
    }

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
