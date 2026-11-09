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

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.ui.screens.content.elements.modEnvironment
import com.movtery.zalithlauncher.ui.screens.content.elements.modsDir
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File

/**
 * ONA Boost: ONA's own FPS mod for phones (source in ona-boost/, built by ona_boost.yml into the
 * "ona-boost" release). It keeps the target FPS by itself by taking the least visible settings
 * lighter one notch at a time and giving them back when the FPS is high again, on top of Sodium /
 * Embeddium and with shaders. Installed in Fabric / Quilt 26.x and Forge 1.20.1 versions; only its
 * own jars (ona-boost-*.jar) are ever added, replaced or removed here, never a player's mod.
 */
object OnaBoostInstaller {
    private const val TAG = "OnaBoostInstaller"
    const val VERSION = "1.1.0"
    private const val BASE = "https://github.com/Hashem2008/ONAlauncher2/releases/download/ona-boost/"
    private const val PREFIX = "ona-boost-"

    /** The ONA Boost jar for this loader and game version, or null when there is none */
    private fun jarFor(loader: String, mc: String): String? = when {
        (loader == "fabric" || loader == "quilt") && mc.startsWith("26.") -> "ona-boost-fabric-26.3-$VERSION.jar"
        loader == "forge" && mc == "1.20.1" -> "ona-boost-forge-1.20.1-$VERSION.jar"
        else -> null
    }

    /** @return the jar name when it was added now, else null */
    fun ensure(version: Version): String? {
        val environment = version.modEnvironment() ?: return null
        val loader = environment.loader ?: return null
        val mc = environment.gameVersion ?: return null
        val modsDir = version.modsDir()
        val wanted = if (AllSettings.onaBoost.getValue()) jarFor(loader, mc) else null
        // older builds of ONA Boost, or ONA Boost switched off: only ONA's own jar goes
        modsDir.listFiles { file -> file.name.startsWith(PREFIX) && file.name.endsWith(".jar") }.orEmpty()
            .filter { it.name != wanted }
            .forEach { old ->
                if (old.delete()) Logger.info(TAG, "Removed ${old.name} from ${version.getVersionName()}")
            }
        if (wanted == null) return null
        writeConfig(version.getGameDir())
        val target = File(modsDir, wanted)
        if (target.isFile) return null
        modsDir.mkdirs()
        return runCatching {
            ModCompat.download(BASE + wanted, target)
            Logger.info(TAG, "$wanted added to ${version.getVersionName()}")
            wanted
        }.onFailure {
            target.delete()
            Logger.warning(TAG, "Could not download $wanted", it)
        }.getOrNull()
    }

    /** config/onaboost.json: the launcher's on / off and target FPS; the mod's own fields stay */
    private fun writeConfig(gameDir: File) = runCatching {
        val file = File(gameDir, "config/onaboost.json")
        val json = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull() ?: JsonObject()
        json.addProperty("enabled", true)
        json.addProperty("targetFps", AllSettings.onaBoostTarget.getValue())
        file.parentFile?.mkdirs()
        file.writeText(GsonBuilder().setPrettyPrinting().create().toJson(json))
    }.onFailure { Logger.warning(TAG, "Could not write onaboost.json", it) }
}
