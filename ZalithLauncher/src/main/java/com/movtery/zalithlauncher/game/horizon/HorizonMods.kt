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

package com.movtery.zalithlauncher.game.horizon

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * ONA: mods, modpacks, shader packs and resource packs made for Horizon (the Rust engine).
 *
 * They come from Horizon's own reviewed registry, horizon-rust/horizon-mods (the list
 * playhorizon.dev/mods and Horizon's PC launcher show), read from its index.json. Java mods
 * (Forge, Fabric...) do not run in Horizon, so they are never offered here.
 *
 * What the phone build can use: content mods (.hmod, data and assets), Horizon shader packs
 * (.hshader) and resource packs. Native mods (.hnmod) are Rust source the PC launcher compiles
 * into the game, which a phone cannot do yet, so they are listed with a link only.
 * Everything goes into Horizon's game folder, the one HorizonActivity runs in
 * (files/horizon-rust/game, see tools/horizon/patch_horizon.py).
 */
object HorizonMods {
    private const val TAG = "HorizonMods"
    const val INDEX_URL = "https://horizon-rust.github.io/horizon-mods/index.json"
    const val WEBSITE = "https://playhorizon.dev/mods"
    private const val MAX_SIZE = 64L * 1024 * 1024
    private const val CACHE_MINUTES = 10

    enum class Kind(val id: String) {
        MOD("mod"), NATIVE_MOD("native-mod"), MODPACK("modpack"), SHADER("shader"), RESOURCE_PACK("resourcepack");

        /** Can the phone build of Horizon use this kind */
        val runsOnPhone: Boolean get() = this != NATIVE_MOD

        companion object {
            fun of(id: String): Kind? = entries.firstOrNull { it.id == id }
        }
    }

    class Required(val id: String, val version: String, val sha256: String)

    class Project(
        val id: String,
        val kind: Kind,
        val name: String,
        val summary: String,
        val authors: List<String>,
        val icon: String?,
        val source: String?,
        val downloads: Long,
        val version: String,
        val game: String?,
        val fileName: String,
        val fileUrl: String,
        val size: Long,
        val sha256: String,
        val requires: List<Required>,
        val allVersions: Map<String, Pair<String, String>>,
    )

    @Volatile private var cached: List<Project>? = null
    @Volatile private var cachedAt = 0L

    /** Horizon's game folder: worlds, options.txt, horizon-mods/ */
    fun gameDir(context: Context): File = File(context.filesDir, "horizon-rust/game")

    /** The registry's projects; cached for a few minutes, as Horizon's own launcher does */
    @Throws(IOException::class)
    fun projects(refresh: Boolean = false): List<Project> {
        val now = System.currentTimeMillis()
        cached?.let { if (!refresh && now - cachedAt < CACHE_MINUTES * 60_000L) return it }
        val root = JsonParser.parseString(httpText(INDEX_URL)).asJsonObject
        val schema = root.get("schema")?.asInt ?: 1
        if (schema > 1) throw IOException("Horizon's mod list needs a newer ONA Launcher (schema $schema)")
        val revoked = root.getAsJsonArray("revoked")?.mapNotNull { it.asJsonObject.str("sha256") }?.toSet().orEmpty()
        val list = root.getAsJsonArray("projects")?.mapNotNull { element ->
            runCatching { parse(element.asJsonObject) }.getOrNull()
        }.orEmpty().filter { it.sha256 !in revoked }
        cached = list
        cachedAt = now
        return list
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun parse(json: JsonObject): Project? {
        val kind = Kind.of(json.str("kind") ?: return null) ?: return null
        val versions = json.getAsJsonArray("versions") ?: return null
        if (versions.size() == 0) return null
        val all = LinkedHashMap<String, Pair<String, String>>()
        versions.forEach { v ->
            val o = v.asJsonObject
            val f = o.getAsJsonObject("file") ?: return@forEach
            val ver = o.str("version") ?: return@forEach
            all[ver] = (f.str("url") ?: return@forEach) to (f.str("sha256") ?: return@forEach)
        }
        val newest = versions[0].asJsonObject
        val file = newest.getAsJsonObject("file") ?: return null
        return Project(
            id = json.str("id") ?: return null,
            kind = kind,
            name = json.str("name") ?: json.str("id")!!,
            summary = json.str("summary").orEmpty(),
            authors = json.getAsJsonArray("authors")?.map { it.asString }.orEmpty(),
            icon = json.str("icon")?.takeIf { it.startsWith("https://") },
            source = json.str("source")?.takeIf { it.startsWith("https://") },
            downloads = json.get("downloads")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            version = newest.str("version").orEmpty(),
            game = newest.str("game"),
            fileName = file.str("name") ?: return null,
            fileUrl = file.str("url") ?: return null,
            size = file.get("size")?.asLong ?: 0L,
            sha256 = file.str("sha256")?.lowercase() ?: return null,
            requires = newest.getAsJsonArray("requires")?.mapNotNull { r ->
                val o = r.asJsonObject
                Required(o.str("id") ?: return@mapNotNull null, o.str("version").orEmpty(), o.str("sha256").orEmpty().lowercase())
            }.orEmpty(),
            allVersions = all,
        )
    }

    /** Where a project's file goes in the game folder */
    private fun target(context: Context, kind: Kind, fileName: String): File? {
        val dir = when (kind) {
            Kind.MOD -> "horizon-mods"
            Kind.SHADER -> "shaderpacks"
            Kind.RESOURCE_PACK -> "resourcepacks"
            else -> return null
        }
        return File(gameDir(context), "$dir/$fileName")
    }

    fun isInstalled(context: Context, project: Project): Boolean = when (project.kind) {
        Kind.MODPACK -> project.requires.isNotEmpty() && project.requires.all { req ->
            projects().firstOrNull { it.id == req.id }?.let { isInstalled(context, it) } == true
        }
        Kind.NATIVE_MOD -> false
        else -> target(context, project.kind, project.fileName)?.isFile == true
    }

    /**
     * Download and check a project into Horizon's game folder: content mods are also turned on
     * in horizon-mods.txt, a modpack installs the mods it lists. Horizon reads mods when it
     * starts, so it has to be restarted after this.
     */
    @Throws(IOException::class)
    fun install(context: Context, project: Project) {
        when (project.kind) {
            Kind.NATIVE_MOD -> throw IOException("Native mods need Horizon's PC launcher to build them")
            Kind.MODPACK -> {
                val all = projects()
                project.requires.forEach { req ->
                    val mod = all.firstOrNull { it.id == req.id }
                        ?: throw IOException("${req.id} is not in Horizon's list")
                    val (url, sha) = mod.allVersions[req.version] ?: (mod.fileUrl to mod.sha256)
                    val name = url.substringAfterLast('/')
                    if (mod.kind == Kind.NATIVE_MOD) {
                        Logger.info(TAG, "${project.id}: native mod ${mod.id} skipped on the phone")
                        return@forEach
                    }
                    installFile(context, mod.kind, mod.id, url, name, req.sha256.ifEmpty { sha })
                }
            }
            else -> installFile(context, project.kind, project.id, project.fileUrl, project.fileName, project.sha256)
        }
    }

    private fun installFile(context: Context, kind: Kind, id: String, url: String, fileName: String, sha256: String) {
        if (!url.startsWith("https://github.com/")) throw IOException("Not a Horizon registry file: $url")
        if (fileName.contains('/') || fileName.contains("..") || fileName.startsWith(".")) throw IOException("Bad file name")
        val output = target(context, kind, fileName) ?: return
        output.parentFile?.mkdirs()
        val part = File(output.parentFile, ".$fileName.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open(url)
        try {
            var total = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_SIZE) throw IOException("$fileName is too big")
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: IOException) {
            part.delete()
            throw e
        } finally {
            connection.disconnect()
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha256.isNotEmpty() && hash != sha256) {
            part.delete()
            throw IOException("$fileName did not match its checksum")
        }
        output.delete()
        if (!part.renameTo(output)) throw IOException("Cannot write $fileName")
        if (kind == Kind.MOD) turnOn(context, id)
        Logger.info(TAG, "Installed $fileName for Horizon")
    }

    /** Mods start off in Horizon: horizon-mods.txt keeps `mod.<id>:true` for the ones turned on */
    private fun turnOn(context: Context, id: String) {
        val file = File(gameDir(context), "horizon-mods.txt")
        val lines = if (file.isFile) file.readLines().toMutableList() else mutableListOf()
        val key = "mod.$id:"
        val index = lines.indexOfFirst { it.startsWith(key) }
        if (index >= 0) lines[index] = "${key}true" else lines.add("${key}true")
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(5) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "ONA-Launcher")
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            val code = connection.responseCode
            if (code in 300..399) {
                val next = connection.getHeaderField("Location") ?: throw IOException("HTTP $code")
                connection.disconnect()
                if (!next.startsWith("https://")) throw IOException("Insecure redirect")
                current = next
                return@repeat
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw IOException("HTTP $code")
            }
            return connection
        }
        throw IOException("Too many redirects")
    }

    private fun httpText(url: String): String {
        val connection = open(url)
        return try {
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }
}
