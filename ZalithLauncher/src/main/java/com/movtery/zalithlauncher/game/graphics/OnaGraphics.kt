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

/** ONA Graphics levels = the profiles of "ONA Mobile Shaders" (shaders.properties), weakest first */
enum class OnaGraphicsLevel(@StringRes val titleRes: Int) {
    POTATO(R.string.ona_graphics_potato),
    LOW(R.string.ona_graphics_low),
    MEDIUM(R.string.ona_graphics_medium),
    HIGH(R.string.ona_graphics_high),
    ULTRA(R.string.ona_graphics_ultra),
    REALISTIC(R.string.ona_graphics_realistic),
    ULTRA_REALISTIC(R.string.ona_graphics_ultra_realistic),
    IMPOSSIBLE(R.string.ona_graphics_impossible);

    /** From MEDIUM up the shader reads the PBR maps (bumps, shine, glow) */
    val usesPbr: Boolean get() = ordinal >= MEDIUM.ordinal
}

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
    private const val PBR_GENERATION = 2
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
        var options = profileOptions(context, level)
        if (loader == Loader.OPTIFINE) {
            val capped = optifineCaps(options)
            if (capped != options) lines.add(context.getString(R.string.ona_graphics_optifine_capped))
            options = capped
        }
        File(packsDir, "$SHADER_NAME.txt").writeText(
            options.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=$value" }
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
            options.writeText(profileOptions(context, autoLevel(tier)).entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k=$v" })
        }
        Logger.info(TAG, "${version.getVersionName()}: $SHADER_NAME ready in the shader list")
    }

    private const val REVISION_FILE = "ona_revision.txt"

    /**
     * The option values of a level, read from the pack's own shaders.properties
     * ("profile.HIGH=SHADOWS !GODRAYS PBR=2 ..."), so the launcher and the in-game profile agree.
     */
    fun profileOptions(context: Context, level: OnaGraphicsLevel): Map<String, String> {
        val properties = context.assets.open("$ASSET_DIR/shaders/shaders.properties").use { it.readBytes().decodeToString() }
        val line = properties.lines().firstOrNull { it.startsWith("profile.${level.name}=") } ?: return emptyMap()
        val options = linkedMapOf<String, String>()
        line.substringAfter('=').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { token ->
            when {
                token.contains('=') -> options[token.substringBefore('=')] = token.substringAfter('=')
                token.startsWith("!") -> options[token.substring(1)] = "false"
                else -> options[token] = "true"
            }
        }
        return options
    }

    /**
     * OptiFine draws the world without Sodium and renders the whole shadow distance again for the
     * shadow map, so on a phone the big levels are CPU bound (FPS low even at 25% resolution).
     * Same look, lighter numbers: shadow map 2048 / 96 blocks, fewer samples, no mob shadows.
     */
    private fun optifineCaps(options: Map<String, String>): Map<String, String> {
        val out = options.toMutableMap()
        fun capInt(key: String, max: Int) {
            out[key]?.toIntOrNull()?.let { if (it > max) out[key] = max.toString() }
        }
        fun capFloat(key: String, max: Float) {
            out[key]?.toFloatOrNull()?.let { if (it > max) out[key] = max.toString() }
        }
        capInt("shadowMapResolution", 2048)
        capFloat("shadowDistance", 96.0f)
        capInt("SSAO_SAMPLES", 8)
        capInt("SSR_STEPS", 16)
        capInt("VL_STEPS", 8)
        capInt("POM_SAMPLES", 8)
        if (out["SHADOW_ENTITIES"] == "true") out["SHADOW_ENTITIES"] = "false"
        return out
    }

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

    /**
     * The launcher setting "ONA Graphics" (not OFF): every launch of a version that can load
     * shaders runs with ONA Mobile Shaders at that level. The full setup (loader, pack, options,
     * PBR) runs when the level or the pack changed; otherwise only "shaders on, our pack" is kept.
     * Versions without a mod loader are left alone.
     * @return what was done (for a toast), or null when nothing needed doing
     */
    suspend fun applyOnLaunch(context: Context, version: Version, level: OnaGraphicsLevel): Result? = withContext(Dispatchers.IO) {
        if (version.modEnvironment()?.loader == null && detectLoader(version) == Loader.NONE) return@withContext null
        val stamp = File(version.getVersionPath(), "ona_graphics.txt")
        val wanted = "${level.name}|${packRevision(context)}"
        val loader = detectLoader(version)
        if (loader != Loader.NONE && runCatching { stamp.readText().trim() }.getOrNull() == wanted) {
            selectShader(version.getGameDir(), loader)
            // packs added or removed since: the maps follow the textures that are on now
            val pbr = level.usesPbr && runCatching { ensurePbrPack(context, version) { } }.getOrDefault(false)
            setPbrPack(version.getGameDir(), enabled = pbr)
            return@withContext null
        }
        val result = apply(context, version, level) { }
        if (result.ok) stamp.writeText(wanted)
        result
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
        val already = list.any { it.asString == id }
        if (enabled && already) return // keep where the player put it
        val kept = JsonArray()
        list.forEach { if (it.asString != id) kept.add(it) }
        if (kept.size() == 0) kept.add("vanilla")
        if (enabled) {
            // just above the built-in packs, below every resource pack the player added: a pack
            // with its own textures and maps (Patrix...) always wins over ours
            val items = kept.map { it.asString }.toMutableList()
            val lastBuiltIn = items.indexOfLast { !it.startsWith("file/") }
            items.add(lastBuiltIn + 1, id)
            kept.apply { while (size() > 0) remove(0) }
            items.forEach { kept.add(it) }
        }
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
    internal fun clientJar(version: Version): File? {
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

    /** A resource pack zip / folder or the client jar, read the same way */
    private class PackSource(val file: File) : java.io.Closeable {
        private val zip: ZipFile? = if (file.isFile) runCatching { ZipFile(file) }.getOrNull() else null
        fun has(path: String): Boolean = if (zip != null) zip.getEntry(path) != null else File(file, path).isFile
        fun bytes(path: String): ByteArray? = if (zip != null) {
            zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
        } else File(file, path).takeIf { it.isFile }?.readBytes()
        fun blockTextures(prefix: String): List<String> = if (zip != null) {
            zip.entries().toList().map { it.name }
                .filter { it.startsWith(prefix) && it.endsWith(".png") && !it.substring(prefix.length).contains('/') }
                .map { it.substring(prefix.length).removeSuffix(".png") }
        } else File(file, prefix).listFiles { f -> f.name.endsWith(".png") }.orEmpty().map { it.name.removeSuffix(".png") }
        override fun close() { runCatching { zip?.close() } }
    }

    /** The resource packs options.txt enables, highest priority first, without ours */
    private fun activePacks(version: Version): List<File> {
        val gameDir = version.getGameDir()
        val line = runCatching { File(gameDir, "options.txt").readLines().firstOrNull { it.startsWith("resourcePacks:") } }.getOrNull() ?: return emptyList()
        val ids = runCatching { JsonParser.parseString(line.substringAfter(':')).asJsonArray.map { it.asString } }.getOrDefault(emptyList())
        return ids.filter { it.startsWith("file/") && it != "file/$PBR_FILE" }
            .map { File(File(gameDir, "resourcepacks"), it.removePrefix("file/")) }
            .filter { it.exists() }
            .reversed()
    }

    /**
     * Makes ONA Mobile PBR from the textures the game really shows: every block texture comes
     * from the highest resource pack that has it (Patrix 32x, an HD pack...), else from the game.
     * Textures whose pack brings its own normal / specular maps are left to that pack.
     * Made again when the enabled packs change.
     * @return true when the pack is there and current
     */
    private fun ensurePbrPack(context: Context, version: Version, onProgress: (String) -> Unit): Boolean {
        val jar = clientJar(version) ?: return false
        val packsDir = File(version.getGameDir(), "resourcepacks").apply { mkdirs() }
        val pack = File(packsDir, PBR_FILE)
        val packs = activePacks(version)
        val stamp = "$PBR_GENERATION|${jar.name}|${jar.length()}|" + packs.joinToString(",") { "${it.name}:${it.length()}" }
        val marker = File(packsDir, ".ona_mobile_pbr")
        if (pack.isFile && runCatching { marker.readText() }.getOrNull() == stamp) return true

        onProgress(context.getString(R.string.ona_graphics_making_pbr))
        val temp = File(packsDir, "$PBR_FILE.tmp")
        var count = 0
        val prefix = "assets/minecraft/textures/block/"
        val sources = packs.map { PackSource(it) } + PackSource(jar)
        try {
            val names = sources.flatMap { it.blockTextures(prefix) }.toSortedSet()
                .filter { OnaPbr.shouldProcess(it) && !it.endsWith("_n") && !it.endsWith("_s") }
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { out ->
                out.putNextEntry(ZipEntry("pack.mcmeta"))
                out.write(ZipFile(jar).use { packMeta(it, version) }.toByteArray())
                out.closeEntry()
                val options = BitmapFactory.Options().apply {
                    inPremultiplied = false
                    inScaled = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                names.forEachIndexed { i, name ->
                    val path = "$prefix$name.png"
                    val source = sources.firstOrNull { it.has(path) } ?: return@forEachIndexed
                    // the pack's own PBR maps win, nothing to make
                    if (source.file != jar && (source.has("$prefix${name}_n.png") || source.has("$prefix${name}_s.png"))) return@forEachIndexed
                    if (i % 100 == 0) onProgress(context.getString(R.string.ona_graphics_making_pbr_progress, i, names.size))
                    val bytes = source.bytes(path) ?: return@forEachIndexed
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
                    source.bytes("$path.mcmeta")?.let { meta ->
                        listOf("_n", "_s").forEach { suffix ->
                            out.putNextEntry(ZipEntry("$prefix$name$suffix.png.mcmeta"))
                            out.write(meta)
                            out.closeEntry()
                        }
                    }
                    count++
                }
            }
        } finally {
            sources.forEach { it.close() }
        }
        if (count == 0) {
            temp.delete()
            return false
        }
        pack.delete()
        if (!temp.renameTo(pack)) return false
        marker.writeText(stamp)
        Logger.info(TAG, "ONA Mobile PBR: maps for $count textures from ${packs.size} pack(s) + ${jar.name}")
        return true
    }

    internal fun writePng(out: ZipOutputStream, path: String, pixels: IntArray, w: Int, h: Int) {
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
    internal fun packMeta(
        zip: ZipFile,
        version: Version,
        description: String = "ONA Mobile PBR: bumps, shine and glow for ONA Mobile Shaders"
    ): String {
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
