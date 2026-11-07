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

package com.movtery.zalithlauncher.components.jre

import android.content.Context
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.ZLApplication
import com.movtery.zalithlauncher.game.multirt.RuntimesManager
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.mod.ModCompat
import com.movtery.zalithlauncher.utils.device.Architecture
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File

/**
 * ONA: Java for APKs that carry none (ONA Performance). The runtime a version needs (8, 17, 21 or
 * 25) is downloaded once, before its first launch, from this repository's "ona-runtimes" release
 * (the same files the normal APKs carry in assets/runtimes, published by build.yml), and installed
 * like the bundled ones (same name "Internal-N", so every setting keeps working).
 */
object RuntimeDownloader {
    private const val TAG = "RuntimeDownloader"
    private const val BASE = "https://github.com/Hashem2008/ONAlauncher2/releases/download/ona-runtimes/"

    /** Download size in MB for the toast (universal + this CPU's binaries) */
    fun sizeMb(jre: Jre): Int = when (jre) {
        Jre.JRE_8 -> 14
        Jre.JRE_17 -> 22
        Jre.JRE_21 -> 26
        Jre.JRE_25 -> 39
    }

    /** Java major version the version's json asks for (its own or the vanilla one it inherits) */
    fun requiredJava(version: Version): Int {
        val dir = version.getVersionPath()
        fun read(json: File): Pair<Int?, String?> = runCatching {
            val root = JsonParser.parseString(json.readText()).asJsonObject
            val major = root.getAsJsonObject("javaVersion")?.get("majorVersion")?.asInt
            major to root.get("inheritsFrom")?.asString
        }.getOrDefault(null to null)
        val (own, inherits) = read(File(dir, "${dir.name}.json"))
        if (own != null && own > 0) return own
        if (inherits != null) {
            read(File(dir.parentFile, "$inherits/$inherits.json")).first?.takeIf { it > 0 }?.let { return it }
        }
        return 8
    }

    /** The ONA runtime that runs this Java version */
    fun pick(major: Int): Jre = Jre.entries.sortedBy { it.majorVersion }.firstOrNull { it.majorVersion >= major } ?: Jre.JRE_25

    private fun bundled(context: Context, jre: Jre): Boolean =
        runCatching { context.assets.list(jre.jrePath)?.isNotEmpty() == true }.getOrDefault(false)

    /**
     * @return the runtime to download for this version, or null when a fitting one is installed,
     * the APK carries it, or the version picks its own runtime
     */
    fun missingFor(context: Context, version: Version): Jre? {
        if (version.getJavaRuntime().isNotEmpty()) return null
        val major = requiredJava(version)
        if (RuntimesManager.getNearestJreName(major) != null) return null
        val jre = pick(major)
        if (bundled(context, jre)) return null
        return jre
    }

    /** Downloads and installs [jre]. @return true when it is ready */
    suspend fun install(context: Context, jre: Jre, onProgress: (String) -> Unit = {}): Boolean {
        val arch = Architecture.archAsString(ZLApplication.DEVICE_ARCHITECTURE)
        val prefix = jre.jrePath.substringAfterLast('/') // jre-25
        val cache = File(context.cacheDir, "ona-runtime-download").apply { mkdirs() }
        val universal = File(cache, "$prefix-universal.tar.xz")
        val bins = File(cache, "$prefix-bin-$arch.tar.xz")
        val versionFile = File(cache, "$prefix-version")
        return runCatching {
            onProgress(prefix)
            ModCompat.download(BASE + "$prefix-version", versionFile)
            ModCompat.download(BASE + "$prefix-universal.tar.xz", universal)
            ModCompat.download(BASE + "$prefix-bin-$arch.tar.xz", bins)
            universal.inputStream().use { universalIn ->
                bins.inputStream().use { binsIn ->
                    RuntimesManager.installRuntimeBinPack(
                        universalFileInputStream = universalIn,
                        platformBinsInputStream = binsIn,
                        name = jre.jreName,
                        binPackVersion = versionFile.readText().trim(),
                    )
                }
            }
            RuntimesManager.postPrepare(jre.jreName)
            RuntimesManager.getRuntimes(forceLoad = true)
            Logger.info(TAG, "${jre.jreName} downloaded and installed")
            true
        }.onFailure {
            Logger.warning(TAG, "Could not download ${jre.jreName}", it)
        }.getOrDefault(false).also {
            universal.delete()
            bins.delete()
            versionFile.delete()
        }
    }
}
