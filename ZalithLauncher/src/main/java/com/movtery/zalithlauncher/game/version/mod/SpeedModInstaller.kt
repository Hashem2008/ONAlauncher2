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

import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.utils.isLowerVer
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.ui.screens.content.elements.modEnvironment
import com.movtery.zalithlauncher.ui.screens.content.elements.modsDir
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File

/**
 * ONA: a modded version without any rendering optimiser (Sodium / Embeddium...) draws with
 * Minecraft's own renderer, which is several times slower on phone GPUs. Such versions get the
 * one that fits their loader and game version from Modrinth (metadata checked by [ModCompat]).
 * Never added next to OptiFine or another optimiser; a version where nothing fits is asked again
 * only after a few days. Setting "onaAutoSpeedMod" (default on).
 */
object SpeedModInstaller {
    private const val TAG = "SpeedModInstaller"
    private const val CHECK_FILE = "ona_speed_mod_checked.txt"
    private const val RETRY_MS = 3L * 24 * 60 * 60 * 1000

    /** Rendering optimisers and mods that do not go together with one */
    private val PRESENT = listOf(
        "sodium", "embeddium", "rubidium", "xenon", "magnesium", "optifine", "optifabric",
        "nvidium", "celeritas", "vintagium", "relictium", "canvas"
    )

    /** @return the installed jar's name, or null when nothing was added */
    fun ensure(version: Version): String? {
        if (!AllSettings.autoSpeedMod.getValue()) return null
        val environment = version.modEnvironment() ?: return null
        val loader = environment.loader ?: return null
        val mc = environment.gameVersion ?: return null
        if (mc.isLowerVer("1.16")) return null // Sodium and its ports start at 1.16
        val modsDir = version.modsDir()
        val jars = modsDir.listFiles { file -> file.name.endsWith(".jar") }.orEmpty()
        if (jars.isEmpty()) return null // vanilla-like versions are not changed
        if (jars.any { jar -> PRESENT.any { jar.name.lowercase().contains(it) } }) return null

        val check = File(version.getVersionPath(), CHECK_FILE)
        val last = runCatching { check.readText().trim().toLong() }.getOrDefault(0L)
        if (System.currentTimeMillis() - last < RETRY_MS) return null

        val candidates = when (loader) {
            "fabric", "quilt" -> listOf("sodium")
            "neoforge" -> listOf("sodium", "embeddium")
            "forge" -> listOf("embeddium")
            else -> emptyList()
        }
        for (slug in candidates) {
            val installed = runCatching { ModCompat.installCompatible(slug, modsDir, environment, slug) }
                .onFailure { Logger.warning(TAG, "$slug for ${version.getVersionName()} failed", it) }
                .getOrNull()
            if (installed != null) {
                Logger.info(TAG, "${installed.name} added to ${version.getVersionName()}")
                runCatching { check.delete() }
                return installed.name
            }
        }
        runCatching { check.writeText(System.currentTimeMillis().toString()) }
        Logger.info(TAG, "No rendering optimiser fits ${version.getVersionName()} ($loader $mc)")
        return null
    }
}
