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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.mod.ModCompat
import com.movtery.zalithlauncher.ui.screens.content.elements.modEnvironment
import com.movtery.zalithlauncher.ui.screens.content.elements.modsDir
import com.movtery.zalithlauncher.utils.device.DeviceTier
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** ONA Graphics levels, the profiles of "ONA Mobile Shaders" */
enum class OnaGraphicsLevel(@StringRes val titleRes: Int, val options: Map<String, String>) {
    POTATO(R.string.ona_graphics_potato, levelOptions(false, 0, false, false, false, 0, false, false, 512, "32.0")),
    LOW(R.string.ona_graphics_low, levelOptions(false, 0, true, true, true, 0, false, false, 512, "32.0")),
    MEDIUM(R.string.ona_graphics_medium, levelOptions(true, 0, true, true, true, 1, false, false, 512, "48.0")),
    HIGH(R.string.ona_graphics_high, levelOptions(true, 1, true, true, true, 2, true, false, 1024, "64.0")),
    ULTRA(R.string.ona_graphics_ultra, levelOptions(true, 2, true, true, true, 2, true, true, 2048, "96.0"));

    /** From MEDIUM up the shader reads the PBR maps (bumps, shine, glow) */
    val usesPbr: Boolean get() = ordinal >= MEDIUM.ordinal
}

private fun levelOptions(
    shadows: Boolean, filter: Int, waving: Boolean, waves: Boolean, reflections: Boolean,
    pbr: Int, bloom: Boolean, godrays: Boolean, shadowRes: Int, shadowDistance: String
) = linkedMapOf(
    "SHADOWS" to shadows.toString(),
    "SHADOW_FILTER" to filter.toString(),
    "WAVING_PLANTS" to waving.toString(),
    "WATER_WAVES" to waves.toString(),
    "WATER_REFLECTIONS" to reflections.toString(),
    "PBR" to pbr.toString(),
    "BLOOM" to bloom.toString(),
    "GODRAYS" to godrays.toString(),
    "shadowMapResolution" to shadowRes.toString(),
    "shadowDistance" to shadowDistance,
)

/**
 * ONA Graphics: "ONA Mobile Shaders" (ONA's own shader pack, made for phone GPUs, in the APK
 * assets) and "ONA Mobile PBR" (bump / shine / glow maps worked out on the phone from the game's
 * own block textures), installed together into a version and switched to one level.
 */
object OnaGraphics {
    private const val TAG = "OnaGraphics"
    const val SHADER_NAME = "ONA Mobile Shaders"
    const val PBR_FILE = "ONA Mobile PBR.zip"
    /** Bump when OnaPbr changes: packs made by an older launcher are made again */
    private const val PBR_GENERATION = 1
    private const val ASSET_DIR = "ona/shaderpack"

    fun autoLevel(tier: DeviceTier): OnaGraphicsLevel = when (tier) {
        DeviceTier.LOW -> OnaGraphicsLevel.POTATO
        DeviceTier.MID -> OnaGraphicsLevel.LOW
        DeviceTier.HIGH -> OnaGraphicsLevel.MEDIUM
        DeviceTier.FLAGSHIP -> OnaGraphicsLevel.HIGH
    }

    class Result(val ok: Boolean, val lines: List<String>)

    private enum class Loader { IRIS, OCULUS, OPTIFINE, NONE }

    /** @param level null turns ONA Graphics off (shaders off, PBR pack out of the list) */
    suspend fun apply(
        context: Context,
        version: Version,
        level: OnaGraphicsLevel?,
        onProgress: (String) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        val gameDir = version.getGameDir()
        if (level == null) {
            setShaderEnabled(gameDir, enabled = false)
            setPbrPack(gameDir, enabled = false)
            return@withContext Result(true, listOf(context.getString(R.string.ona_graphics_turned_off)))
        }

        // 1. A shader loader: Iris (Fabric, Quilt, NeoForge), Oculus (Forge), or OptiFine
        var loader = detectLoader(version)
        if (loader == Loader.NONE) {
            val env = version.modEnvironment()
            val project = when (env?.loader) {
                "fabric", "quilt", "neoforge" -> "iris"
                "forge" -> "oculus"
                else -> null
            }
            if (project == null || env == null) {
                return@withContext Result(false, listOf(context.getString(R.string.ona_graphics_needs_loader)))
            }
            onProgress(context.getString(R.string.ona_graphics_installing_loader, project))
            val installed = runCatching {
                version.modsDir().mkdirs()
                ModCompat.installCompatible(project, version.modsDir(), env, project)
            }.onFailure { Logger.warning(TAG, "Could not install $project", it) }.getOrNull()
            if (installed == null) {
                return@withContext Result(false, listOf(context.getString(R.string.ona_graphics_loader_failed, project)))
            }
            lines.add(context.getString(R.string.ona_graphics_loader_added, installed.name))
            loader = if (project == "iris") Loader.IRIS else Loader.OCULUS
        }

        // 2. The shader pack, fresh from the APK, and the options of the level
        onProgress(context.getString(R.string.ona_graphics_copying))
        val packsDir = File(gameDir, "shaderpacks").apply { mkdirs() }
        installPack(context, packsDir)
        File(packsDir, "$SHADER_NAME.txt").writeText(
            level.options.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" }
        )
        selectShader(gameDir, loader)
        lines.add(context.getString(R.string.ona_graphics_level_set, context.getString(level.titleRes)))

        // 3. PBR maps from MEDIUM up
        if (level.usesPbr) {
            val made = runCatching { ensurePbrPack(context, version, onProgress) }
                .onFailure { Logger.warning(TAG, "PBR pack failed", it) }
                .getOrDefault(false)
            if (made) {
                setPbrPack(gameDir, enabled = true)
                lines.add(context.getString(R.string.ona_graphics_pbr_on))
            } else {
                lines.add(context.getString(R.string.ona_graphics_pbr_failed))
            }
        } else {
            setPbrPack(gameDir, enabled = false)
        }
        Logger.info(TAG, "${version.getVersionName()}: ONA Graphics $level ($loader)")
        Result(true, lines)
    }

    /**
     * ONA Mobile Shaders comes with every version that can load shaders (Iris, Oculus, OptiFine),
     * whatever renderer it uses: it is ready in the game's shader list, nothing is switched on.
     * A newer pack from an updated launcher replaces the old one; the player's options stay.
     */
    fun offerPack(context: Context, version: Version) {
        if (detectLoader(version) == Loader.NONE) return
        val packsDir = File(version.getGameDir(), "shaderpacks")
        val marker = File(File(packsDir, SHADER_NAME), REVISION_FILE)
        if (marker.isFile && marker.readText().trim() == packRevision(context)) return
        packsDir.mkdirs()
        installPack(context, packsDir)
        val options = File(packsDir, "$SHADER_NAME.txt")
        if (!options.isFile) {
            val tier = com.movtery.zalithlauncher.utils.device.DeviceProfiler.current()?.tier ?: DeviceTier.MID
            options.writeText(autoLevel(tier).options.entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k=$v" })
        }
        Logger.info(TAG, "${version.getVersionName()}: $SHADER_NAME ready in the shader list")
    }

    private const val REVISION_FILE = "ona_revision.txt"

    /** Checksum of the pack in the APK: changes whenever the shader code changes */
    private fun packRevision(context: Context): String {
        val crc = java.util.zip.CRC32()
        fun walk(path: String) {
            val children = context.assets.list(path).orEmpty()
            if (children.isEmpty()) {
                crc.update(path.toByteArray())
                context.assets.open(path).use { crc.update(it.readBytes()) }
            } else children.sorted().forEach { walk("$path/$it") }
        }
        walk(ASSET_DIR)
        return java.lang.Long.toHexString(crc.value)
    }

    private fun installPack(context: Context, packsDir: File) {
        val packDir = File(packsDir, SHADER_NAME)
        packDir.deleteRecursively()
        copyAssets(context, ASSET_DIR, packDir)
        File(packDir, REVISION_FILE).writeText(packRevision(context))
    }

    private fun detectLoader(version: Version): Loader {
        val jars = version.modsDir().listFiles { file -> file.name.endsWith(".jar") }.orEmpty()
            .map { it.name.lowercase() }
        return when {
            jars.any { it.contains("oculus") } -> Loader.OCULUS
            jars.any { it.contains("iris") } -> Loader.IRIS
            jars.any { it.contains("optifine") || it.contains("optifabric") } -> Loader.OPTIFINE
            version.getVersionName().contains("optifine", ignoreCase = true) -> Loader.OPTIFINE
            else -> Loader.NONE
        }
    }

    /** Iris / Oculus config, or OptiFine's optionsshaders.txt: our pack, shaders on */
    private fun selectShader(gameDir: File, loader: Loader) {
        when (loader) {
            Loader.OPTIFINE -> writeKeyValues(File(gameDir, "optionsshaders.txt"), mapOf("shaderPack" to SHADER_NAME))
            Loader.OCULUS -> writeKeyValues(File(gameDir, "config/oculus.properties"), mapOf("shaderPack" to SHADER_NAME, "enableShaders" to "true"))
            else -> writeKeyValues(File(gameDir, "config/iris.properties"), mapOf("shaderPack" to SHADER_NAME, "enableShaders" to "true"))
        }
    }

    private fun setShaderEnabled(gameDir: File, enabled: Boolean) {
        listOf("config/iris.properties", "config/oculus.properties").forEach { path ->
            val file = File(gameDir, path)
            if (file.isFile) writeKeyValues(file, mapOf("enableShaders" to enabled.toString()))
        }
        val optifine = File(gameDir, "optionsshaders.txt")
        if (!enabled && optifine.isFile) writeKeyValues(optifine, mapOf("shaderPack" to "OFF"))
    }

    /** Sets keys in a key=value file, keeping every other line */
    private fun writeKeyValues(file: File, values: Map<String, String>) {
        file.parentFile?.mkdirs()
        val remaining = values.toMutableMap()
        val lines = (if (file.isFile) file.readLines() else emptyList()).map { line ->
            val key = line.substringBefore('=').trim()
            remaining.remove(key)?.let { "$key=$it" } ?: line
        }
        file.writeText((lines + remaining.map { (k, v) -> "$k=$v" }).joinToString("\n", postfix = "\n"))
    }

    private fun copyAssets(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        children.forEach { copyAssets(context, "$assetPath/$it", File(target, it)) }
    }

    // ---- ONA Mobile PBR ------------------------------------------------------------------

    /** options.txt resourcePacks: our pack last (highest priority) or out of the list */
    private fun setPbrPack(gameDir: File, enabled: Boolean) {
        val options = File(gameDir, "options.txt")
        if (!options.isFile && !enabled) return
        val id = "file/$PBR_FILE"
        val lines = if (options.isFile) options.readLines().toMutableList() else mutableListOf()
        val index = lines.indexOfFirst { it.startsWith("resourcePacks:") }
        val list = runCatching {
            JsonParser.parseString(lines[index].substringAfter(':')).asJsonArray
        }.getOrNull() ?: JsonArray().apply { add("vanilla") }
        val kept = JsonArray()
        list.forEach { if (it.asString != id) kept.add(it) }
        if (kept.size() == 0) kept.add("vanilla")
        if (enabled) kept.add(id)
        val line = "resourcePacks:" + kept.toString()
        if (index >= 0) lines[index] = line else lines.add(line)
        // a pack Minecraft once called incompatible would stay off: forget that
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

    /** The vanilla client jar the version plays (its own, or the one it inherits) */
    private fun clientJar(version: Version): File? {
        version.getClientJar().takeIf { it.isFile && hasTextures(it) }?.let { return it }
        val json = File(version.getVersionPath(), "${version.getVersionName()}.json")
        val inherits = runCatching {
            JsonParser.parseString(json.readText()).asJsonObject.get("inheritsFrom")?.asString
        }.getOrNull()
        version.getInheritedClientJar(inherits)?.takeIf { it.isFile }?.let { return it }
        val mc = version.getVersionInfo()?.minecraftVersion ?: return null
        return version.getInheritedClientJar(mc)?.takeIf { it.isFile }
    }

    private fun hasTextures(jar: File): Boolean = runCatching {
        ZipFile(jar).use { it.getEntry("assets/minecraft/textures/block/stone.png") != null }
    }.getOrDefault(false)

    /** @return true when the pack is there and current */
    private fun ensurePbrPack(context: Context, version: Version, onProgress: (String) -> Unit): Boolean {
        val jar = clientJar(version) ?: return false
        val packsDir = File(version.getGameDir(), "resourcepacks").apply { mkdirs() }
        val pack = File(packsDir, PBR_FILE)
        val stamp = "$PBR_GENERATION|${jar.name}|${jar.length()}"
        val marker = File(packsDir, ".ona_mobile_pbr")
        if (pack.isFile && runCatching { marker.readText() }.getOrNull() == stamp) return true

        onProgress(context.getString(R.string.ona_graphics_making_pbr))
        val temp = File(packsDir, "$PBR_FILE.tmp")
        var count = 0
        ZipFile(jar).use { zip ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { out ->
                out.putNextEntry(ZipEntry("pack.mcmeta"))
                out.write(packMeta(zip, version).toByteArray())
                out.closeEntry()
                val prefix = "assets/minecraft/textures/block/"
                val entries = zip.entries().toList().filter {
                    it.name.startsWith(prefix) && it.name.endsWith(".png") && !it.name.substring(prefix.length).contains('/')
                }
                val options = BitmapFactory.Options().apply {
                    inPremultiplied = false
                    inScaled = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                entries.forEachIndexed { i, entry ->
                    val name = entry.name.substring(prefix.length).removeSuffix(".png")
                    if (!OnaPbr.shouldProcess(name)) return@forEachIndexed
                    if (i % 100 == 0) onProgress(context.getString(R.string.ona_graphics_making_pbr_progress, i, entries.size))
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@forEachIndexed
                    val w = bitmap.width
                    val h = bitmap.height
                    val pixels = IntArray(w * h)
                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                    bitmap.recycle()
                    val maps = OnaPbr.generate(name, pixels, w, h)
                    writePng(out, "$prefix${name}_n.png", maps.normal, w, h)
                    writePng(out, "$prefix${name}_s.png", maps.specular, w, h)
                    // animated textures: the maps animate with them
                    zip.getEntry("${entry.name}.mcmeta")?.let { meta ->
                        val metaBytes = zip.getInputStream(meta).use { it.readBytes() }
                        listOf("_n", "_s").forEach { suffix ->
                            out.putNextEntry(ZipEntry("$prefix$name$suffix.png.mcmeta"))
                            out.write(metaBytes)
                            out.closeEntry()
                        }
                    }
                    count++
                }
            }
        }
        if (count == 0) {
            temp.delete()
            return false
        }
        pack.delete()
        if (!temp.renameTo(pack)) return false
        marker.writeText(stamp)
        Logger.info(TAG, "ONA Mobile PBR: maps for $count textures from ${jar.name}")
        return true
    }

    private fun writePng(out: ZipOutputStream, path: String, pixels: IntArray, w: Int, h: Int) {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPremultiplied(false) // the alpha channel carries data (emission), keep colours exact
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        val buffer = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
        bitmap.recycle()
        out.putNextEntry(ZipEntry(path))
        buffer.writeTo(out)
        out.closeEntry()
    }

    /** pack.mcmeta with the resource pack format of this very game version */
    private fun packMeta(zip: ZipFile, version: Version): String {
        val description = "ONA Mobile PBR: bumps, shine and glow for ONA Mobile Shaders"
        val pack = JsonObject().apply { addProperty("description", description) }
        val packVersion = runCatching {
            val json = zip.getInputStream(zip.getEntry("version.json")).use { it.readBytes().decodeToString() }
            JsonParser.parseString(json).asJsonObject.get("pack_version")
        }.getOrNull()
        when {
            packVersion != null && packVersion.isJsonObject && packVersion.asJsonObject.has("resource_major") -> {
                // 1.21.9 and newer: major.minor formats
                val major = packVersion.asJsonObject.get("resource_major").asInt
                pack.addProperty("min_format", major)
                pack.addProperty("max_format", major)
            }
            else -> {
                val format = when {
                    packVersion != null && packVersion.isJsonObject && packVersion.asJsonObject.has("resource") ->
                        packVersion.asJsonObject.get("resource").asInt
                    packVersion != null && packVersion.isJsonPrimitive -> packVersion.asInt
                    else -> legacyFormat(version.getVersionInfo()?.minecraftVersion ?: "")
                }
                pack.addProperty("pack_format", format)
                pack.add("supported_formats", JsonArray().apply { add(format); add(format) })
            }
        }
        return GsonBuilder().setPrettyPrinting().create().toJson(JsonObject().apply { add("pack", pack) })
    }

    private fun legacyFormat(mc: String): Int {
        val parts = mc.split('.').mapNotNull { it.toIntOrNull() }
        val minor = parts.getOrElse(1) { 20 }
        val patch = parts.getOrElse(2) { 0 }
        return when {
            minor <= 12 -> 3
            minor <= 14 -> 4
            minor == 15 || (minor == 16 && patch <= 1) -> 5
            minor == 16 -> 6
            minor == 17 -> 7
            minor == 18 -> 8
            minor == 19 && patch <= 2 -> 9
            minor == 19 && patch == 3 -> 12
            minor == 19 -> 13
            else -> 15
        }
    }
}
