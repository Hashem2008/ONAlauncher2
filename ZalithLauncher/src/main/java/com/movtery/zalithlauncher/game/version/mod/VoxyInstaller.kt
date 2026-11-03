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

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.game.plugin.natives.NativePluginManager
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.ui.screens.content.elements.modEnvironment
import com.movtery.zalithlauncher.ui.screens.content.elements.modsDir
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * ONA: gets everything Voxy needs on a phone. The compatibility mod is installed into the
 * version automatically; the three plugin apps (Turnip driver, Kopper Zink renderer, RocksDB
 * native library) are downloaded from the zalith-voxy release (MIT) and handed to Android's
 * installer, since Android only loads a graphics driver from an installed package.
 */
object VoxyInstaller {
    private const val TAG = "VoxyInstaller"
    private const val REPO = "LXK-98/zalith-voxy"
    private const val COMPAT_MOD_SLUG = "voxy-android-compatibility"
    private const val USER_AGENT = "ONALauncher2 (github.com/Hashem2008/ONAlauncher2)"

    enum class Part(val assetPrefix: String, val label: String) {
        TURNIP("turnip-plugin", "Turnip (Vulkan driver)"),
        ZINK("kopper-zink26-plugin", "Kopper Zink (OpenGL 4.6 renderer)"),
        ROCKSDB("rocksdb-native-plugin", "RocksDB (Voxy storage)");

        fun isInstalled(): Boolean = when (this) {
            TURNIP -> VoxySupport.turnipPluginDriverId() != null
            ZINK -> !VoxySupport.missingZinkPlugin()
            ROCKSDB -> runCatching {
                NativePluginManager.getPlugins().any { plugin ->
                    "${plugin.displayName} ${plugin.appName} ${plugin.packageName}".contains("rocksdb", ignoreCase = true)
                }
            }.getOrDefault(false)
        }
    }

    fun missingParts(): List<Part> = Part.entries.filterNot { it.isInstalled() }

    /**
     * Put the Voxy Android compatibility mod into the version when Voxy is there without it.
     * Blocking, network. @return true when it was installed now
     */
    fun ensureCompatMod(version: Version): Boolean {
        if (!VoxySupport.hasVoxy(version) || VoxySupport.hasCompatMod(version)) return false
        val environment = version.modEnvironment() ?: return false
        val modsDir = version.modsDir()
        return runCatching { ModCompat.installCompatible(COMPAT_MOD_SLUG, modsDir, environment, null) != null }
            .onFailure { Logger.warning(TAG, "Could not install the Voxy compat mod", it) }
            .getOrDefault(false)
            .also { if (it) Logger.info(TAG, "Installed the Voxy Android compatibility mod") }
    }

    /** Download a plugin APK of the latest release into the cache. Blocking, network. */
    fun download(context: Context, part: Part): File {
        val url = assetUrl(part) ?: "https://github.com/$REPO/releases/latest/download/${part.assetPrefix}.apk"
        val dir = File(context.cacheDir, "voxy").apply { mkdirs() }
        val target = File(dir, "${part.assetPrefix}.apk")
        val temp = File(dir, "${part.assetPrefix}.part")
        open(url).let { connection ->
            try {
                if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode} for $url")
                connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    /** Hand a downloaded APK to Android's package installer */
    fun openInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** The asset of the newest release whose name starts with the part's prefix */
    private fun assetUrl(part: Part): String? = runCatching {
        val connection = open("https://api.github.com/repos/$REPO/releases/latest")
        try {
            if (connection.responseCode != 200) return@runCatching null
            val json = connection.inputStream.bufferedReader().use { JsonParser.parseString(it.readText()).asJsonObject }
            json.getAsJsonArray("assets")
                ?.map { it.asJsonObject }
                ?.firstOrNull { asset ->
                    val name = asset.get("name").asString
                    name.startsWith(part.assetPrefix) && name.endsWith(".apk")
                }
                ?.get("browser_download_url")?.asString
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
}
