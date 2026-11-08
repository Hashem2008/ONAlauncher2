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

package com.movtery.zalithlauncher.components

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.movtery.zalithlauncher.BuildKeys
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.version.mod.ModCompat
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.device.Architecture
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * ONA Performance: LWJGL (the game's window, input and OpenGL bindings) is not inside the APK.
 * The pack a version needs (3.3.3 or 3.4.1) is downloaded once, on its first launch, from the
 * "ona-runtimes" release, where build.yml publishes exactly the files the normal APKs carry. The
 * pack name carries the jar version and the natives' checksum, so an updated APK fetches the
 * matching pack by itself.
 */
object LwjglDownloader {
    private const val TAG = "LwjglDownloader"
    private const val BASE = "https://github.com/Hashem2008/ONAlauncher2/releases/download/ona-runtimes/"
    private const val MARKER = "ona_pack"

    /** The pack this APK expects for an LWJGL component folder, or null when it is bundled */
    private fun expectedPack(versionDir: String): String? = when (versionDir) {
        "3.3.3" -> BuildKeys.LWJGL333_PACK
        "3.4.1" -> BuildKeys.LWJGL341_PACK
        else -> null
    }?.takeIf { BuildKeys.ONA_PERFORMANCE == "true" && it.isNotEmpty() }

    /**
     * Makes sure the LWJGL component [versionDir] is installed and current.
     * @return false when it should have been downloaded and could not be
     */
    suspend fun ensure(context: Context, versionDir: String): Boolean = withContext(Dispatchers.IO) {
        val pack = expectedPack(versionDir) ?: return@withContext true
        val dir = File(PathManager.DIR_COMPONENTS, "lwjgl/$versionDir")
        val abi = Architecture.archAsStringAndroid(Architecture.getDeviceArchitecture())
        val marker = File(dir, MARKER)
        val current = runCatching { marker.readText().trim() }.getOrNull()
        if (current == pack && File(dir, "natives/$abi/liblwjgl.so").isFile) return@withContext true

        toast(context, context.getString(R.string.ona_lwjgl_downloading, versionDir))
        val zip = File(context.cacheDir, pack)
        val ok = runCatching {
            ModCompat.download(BASE + pack, zip)
            FileUtils.deleteDirectory(dir)
            unzip(zip, dir)
            marker.writeText(pack)
            Logger.info(TAG, "LWJGL $versionDir installed from $pack")
            true
        }.onFailure {
            Logger.warning(TAG, "Could not download LWJGL $versionDir ($pack)", it)
        }.getOrDefault(false)
        zip.delete()
        toast(context, context.getString(if (ok) R.string.ona_lwjgl_ready else R.string.ona_lwjgl_failed, versionDir))
        ok
    }

    private fun unzip(zip: File, target: File) {
        val root = target.canonicalFile
        root.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val out = File(root, entry.name).canonicalFile
                // nothing may land outside the component folder
                if (!out.path.startsWith(root.path + File.separator) && out != root) {
                    throw IOException("Bad entry ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show() }
        }
    }
}
