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
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * ONA Resource (launcher setting, every version): the textures the game uses.
 * - PERFORMANCE: "ONA Performance Pack", made on the phone from the game's own textures: blocks and
 *   items at half size (smaller texture atlas, less memory traffic for the GPU) and no animated
 *   textures except water and lava (no texture uploads every tick). Sits just above vanilla, so
 *   every pack the player added still wins.
 * - 16x ... 512x+: the most downloaded realistic pack of that size from Modrinth (PBR ones first),
 *   on top of the other packs. ONA Mobile Shaders reads its normal / specular maps.
 * - OFF: ONA's pack leaves the list (the file stays, the player's own packs are not touched).
 */
object OnaResource {
    private const val TAG = "OnaResource"
    const val PERFORMANCE_FILE = "ONA Performance Pack.zip"
    private const val STAMP = "ona_resource.txt"
    private const val PERFORMANCE_GENERATION = 1
    private const val BLOCKS = "assets/minecraft/textures/block/"
    private const val ITEMS = "assets/minecraft/textures/item/"
    /** Animated textures that keep moving in the performance pack (still water looks broken) */
    private val KEEP_ANIMATED = setOf("water_still", "water_flow", "lava_still", "lava_flow")

    val CHOICES: List<String> = listOf("OFF", "PERFORMANCE") + OnaTexturePacks.RESOLUTIONS

    /**
     * Puts the chosen textures in place for [version] when the choice changed since the last
     * launch (in-game pack changes are respected while the choice stays the same).
     * @return a line for a toast, or null when nothing was done
     */
    suspend fun applyOnLaunch(context: Context, version: Version, choice: String): String? = withContext(Dispatchers.IO) {
        val stampFile = File(version.getVersionPath(), STAMP)
        val old = runCatching { stampFile.readText().trim() }.getOrDefault("")
        val oldChoice = old.substringBefore('|')
        val oldId = old.substringAfter('|', "")
        if (oldChoice == choice || (old.isEmpty() && choice == "OFF")) {
            // a game update brings new textures: the performance pack follows them
            if (choice == "PERFORMANCE") runCatching { ensurePerformancePack(version) }
            return@withContext null
        }
        val gameDir = version.getGameDir()
        if (oldId.isNotEmpty()) OnaTexturePacks.removePack(gameDir, oldId)
        val result: Pair<String, String> = when (choice) {
            "OFF" -> "" to context.getString(R.string.ona_resource_turned_off)
            "PERFORMANCE" -> {
                val made = runCatching { ensurePerformancePack(version) }
                    .onFailure { Logger.warning(TAG, "Performance pack failed", it) }
                    .getOrDefault(false)
                if (!made) return@withContext context.getString(R.string.ona_resource_failed, choice)
                val id = "file/$PERFORMANCE_FILE"
                OnaTexturePacks.enableAboveVanilla(gameDir, id)
                id to context.getString(R.string.ona_resource_performance_on)
            }
            else -> {
                val pack = OnaTexturePacks.search(choice).firstOrNull()
                val name = pack?.let { OnaTexturePacks.install(version, it) }
                // no stamp: tried again on the next launch
                if (pack == null || name == null) return@withContext context.getString(R.string.ona_resource_failed, choice)
                "file/$name" to context.getString(R.string.ona_resource_pack_on, pack.title, choice)
            }
        }
        stampFile.writeText("$choice|${result.first}")
        Logger.info(TAG, "${version.getVersionName()}: ONA Resource $choice (${result.first})")
        result.second
    }

    /** @return true when the performance pack is there and made from this game's textures */
    private fun ensurePerformancePack(version: Version): Boolean {
        val jar = OnaGraphics.clientJar(version) ?: return false
        val dir = File(version.getGameDir(), "resourcepacks").apply { mkdirs() }
        val pack = File(dir, PERFORMANCE_FILE)
        val marker = File(dir, ".ona_performance_pack")
        val stamp = "$PERFORMANCE_GENERATION|${jar.name}|${jar.length()}"
        if (pack.isFile && runCatching { marker.readText() }.getOrNull() == stamp) return true

        val temp = File(dir, "$PERFORMANCE_FILE.tmp")
        var count = 0
        val options = BitmapFactory.Options().apply {
            inPremultiplied = false
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        ZipFile(jar).use { zip ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { out ->
                out.putNextEntry(ZipEntry("pack.mcmeta"))
                out.write(OnaGraphics.packMeta(zip, version, "ONA Performance Pack: light textures for more FPS").toByteArray())
                out.closeEntry()
                val names = zip.entries().toList().map { it.name }
                    .filter { (it.startsWith(BLOCKS) || it.startsWith(ITEMS)) && it.endsWith(".png") }
                for (path in names) {
                    val bytes = zip.getInputStream(zip.getEntry(path)).use { it.readBytes() }
                    val meta = zip.getEntry("$path.mcmeta")?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: continue
                    val w = bitmap.width
                    var h = bitmap.height
                    val base = path.substringAfterLast('/').removeSuffix(".png")
                    val animated = meta != null && hasAnimation(meta)
                    val keepAnimation = animated && base in KEEP_ANIMATED
                    if (animated && !keepAnimation) {
                        // custom frame sizes are left to the game; else the first square frame only
                        if (hasCustomFrameSize(meta!!) || h < w) {
                            bitmap.recycle()
                            continue
                        }
                        h = w
                    }
                    if (w < 16 || w % 2 != 0 || h % 2 != 0) {
                        bitmap.recycle()
                        continue
                    }
                    val pixels = IntArray(w * h)
                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                    bitmap.recycle()
                    OnaGraphics.writePng(out, path, halve(pixels, w, h), w / 2, h / 2)
                    if (keepAnimation) {
                        out.putNextEntry(ZipEntry("$path.mcmeta"))
                        out.write(meta!!)
                        out.closeEntry()
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
        Logger.info(TAG, "ONA Performance Pack: $count textures at half size from ${jar.name}")
        return true
    }

    private fun hasAnimation(meta: ByteArray): Boolean = runCatching {
        JsonParser.parseString(meta.decodeToString()).asJsonObject.has("animation")
    }.getOrDefault(false)

    private fun hasCustomFrameSize(meta: ByteArray): Boolean = runCatching {
        val animation = JsonParser.parseString(meta.decodeToString()).asJsonObject.getAsJsonObject("animation")
        animation.has("width") || animation.has("height")
    }.getOrDefault(true)

    /**
     * Half size, 2x2 pixels into one. Cut-out textures (leaves, plants: alpha only 0 or 255) stay
     * cut-out, see-through ones (glass, ice) keep their averaged alpha.
     */
    private fun halve(src: IntArray, w: Int, h: Int): IntArray {
        val ow = w / 2
        val oh = h / 2
        val out = IntArray(ow * oh)
        for (y in 0 until oh) {
            for (x in 0 until ow) {
                var r = 0
                var g = 0
                var b = 0
                var aSum = 0
                var opaque = 0
                var binary = true
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val p = src[(y * 2 + dy) * w + x * 2 + dx]
                        val a = p ushr 24
                        if (a != 0 && a != 255) binary = false
                        if (a > 127) opaque++
                        aSum += a
                        // colour weighted by alpha, so invisible pixels do not darken the edges
                        r += ((p shr 16) and 0xFF) * a
                        g += ((p shr 8) and 0xFF) * a
                        b += (p and 0xFF) * a
                    }
                }
                val alpha = if (binary) (if (opaque >= 2) 255 else 0) else aSum / 4
                out[y * ow + x] = if (aSum == 0) 0 else
                    (alpha shl 24) or ((r / aSum) shl 16) or ((g / aSum) shl 8) or (b / aSum)
            }
        }
        return out
    }
}
