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

import com.movtery.zalithlauncher.game.plugin.driver.DriverPluginManager
import com.movtery.zalithlauncher.game.renderer.RendererInterface
import com.movtery.zalithlauncher.game.renderer.Renderers
import com.movtery.zalithlauncher.game.renderer.renderers.KopperZinkRenderer
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.ui.screens.content.elements.modsDir
import java.io.File

/**
 * ONA: Voxy (far distance LOD renderer) needs desktop OpenGL 4.6: compute shaders, indirect
 * multi draw, gl_DrawID, big SSBOs. On Android only Kopper Zink on Mesa Turnip provides that;
 * the GL4ES family and MobileGlues cannot, and Qualcomm's own Vulkan driver crashes compiling
 * Voxy's shader. See https://github.com/LXK-98/zalith-voxy which ships the renderer, driver and
 * RocksDB plugins (Adreno 7xx / 8xx) and a small compatibility mod.
 *
 * For a version with Voxy, ONA Smart therefore picks Kopper Zink (a Zink renderer plugin when one
 * is installed), a Turnip driver plugin when one is installed, and never the system driver.
 */
object VoxySupport {
    const val GUIDE_URL = "https://github.com/LXK-98/zalith-voxy"

    /** The version has Voxy in its mods folder */
    fun hasVoxy(version: Version): Boolean = hasVoxy(version.modsDir())

    fun hasVoxy(modsDir: File): Boolean =
        modsDir.list()?.any { name ->
            val lower = name.lowercase()
            lower.endsWith(".jar") && lower.startsWith("voxy") && !lower.contains("android-compat") && !lower.contains("worldgen")
        } == true

    /** The Android compatibility mod from zalith-voxy is installed next to Voxy */
    fun hasCompatMod(version: Version): Boolean =
        version.modsDir().list()?.any { it.lowercase().contains("voxy") && it.lowercase().contains("android") } == true

    /** A Zink renderer plugin (e.g. the Mesa 25.0.7 Kopper fork) if installed, else the bundled Kopper Zink */
    fun preferredRenderer(): RendererInterface =
        Renderers.getRenderers().firstOrNull { renderer ->
            renderer.getUniqueIdentifier() != KopperZinkRenderer.getUniqueIdentifier() &&
                    renderer.getRendererName().contains("zink", ignoreCase = true)
        } ?: KopperZinkRenderer

    /** The bundled Kopper Zink is used: Voxy may not work, the plugin from zalith-voxy is needed */
    fun missingZinkPlugin(): Boolean = preferredRenderer() === KopperZinkRenderer

    /** Id of an installed Turnip driver plugin, null when only the bundled one exists */
    fun turnipPluginDriverId(): String? =
        runCatching {
            DriverPluginManager.getDriverList().firstOrNull { driver ->
                !driver.isLauncher && driver.name.contains("turnip", ignoreCase = true)
            }?.id
        }.getOrNull()
}
