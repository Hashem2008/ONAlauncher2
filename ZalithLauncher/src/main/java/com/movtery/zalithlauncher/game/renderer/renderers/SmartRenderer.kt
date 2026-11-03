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

package com.movtery.zalithlauncher.game.renderer.renderers

import com.movtery.zalithlauncher.game.renderer.RendererInterface
import com.movtery.zalithlauncher.game.renderer.Renderers
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.mod.VoxySupport
import com.movtery.zalithlauncher.game.version.mod.MobileModRules
import com.movtery.zalithlauncher.game.version.installed.utils.isBiggerVer
import com.movtery.zalithlauncher.game.version.installed.utils.isLowerVer
import com.movtery.zalithlauncher.utils.device.DeviceProfiler
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File

/**
 * ONA Smart: not a renderer of its own but a per launch choice between the bundled ones, from
 * what each of them is good at:
 *  - GL4ES: oldest and lightest, best on 1.16.5 and older
 *  - Krypton Wrapper (NG GL4ES): fast GL 3.x on GLES, best for 1.17 - 1.21.4 without shaders
 *  - MobileGlues: GL 4.x on GLES 3.2, what Iris / Oculus shaders and 1.21.5+ need
 *  - Kopper Zink: desktop GL on Vulkan through Mesa, the most compatible fallback when the
 *    GPU has a good Vulkan driver (Turnip on Adreno 6xx/7xx)
 * The first candidate that supports the game version wins.
 */
object SmartRenderer : RendererInterface {
    const val ID = "6f0c2a1e-ona-smart-auto"
    private const val TAG = "SmartRenderer"

    override fun getRendererId(): String = NGGL4ESRenderer.getRendererId()
    override fun getUniqueIdentifier(): String = ID
    override fun getRendererName(): String = "ONA Smart (Auto)"
    override fun getRendererSummary(): String = "Picks the best renderer for each version, mod set and GPU"
    override fun getRendererEnv(): Lazy<Map<String, String>> = NGGL4ESRenderer.getRendererEnv()
    override fun getDlopenLibrary(): Lazy<List<String>> = NGGL4ESRenderer.getDlopenLibrary()
    override fun getRendererLibrary(): String = NGGL4ESRenderer.getRendererLibrary()

    private val SHADER_MODS = listOf("iris", "oculus", "optifine")
    private val SODIUM_LIKE = listOf("sodium", "embeddium", "rubidium", "xenon")

    /** @return the unique identifier of the renderer this version should start with */
    fun choose(version: Version): String {
        // Voxy needs desktop GL 4.6, only Kopper Zink on Turnip has it
        if (VoxySupport.hasVoxy(version)) {
            val zink = VoxySupport.preferredRenderer()
            lastReason = "${version.getVersionName()} has Voxy -> ${zink.getRendererName()}"
            Logger.info(TAG, lastReason)
            return zink.getUniqueIdentifier()
        }
        val mc = version.getVersionInfo()?.minecraftVersion ?: return NGGL4ESRenderer.getUniqueIdentifier()
        val gameDir = version.getGameDir()
        val modNames = File(gameDir, "mods").list()?.filter { it.endsWith(".jar") }?.map { it.lowercase() }.orEmpty()
        val shaderMod = modNames.any { name -> SHADER_MODS.any { name.contains(it) } }
        val shaderPacks = File(gameDir, "shaderpacks").listFiles()?.any { !it.name.endsWith(".txt") } == true
        val shaders = shaderMod || shaderPacks && modNames.any { name -> SODIUM_LIKE.any { name.contains(it) } }
        val profile = DeviceProfiler.current()
        val goodVulkan = profile?.let { it.vulkan && it.turnipRecommended } ?: false
        // Any Vulkan 1.1 GPU can run Zink on its own driver, kept as a later fallback
        val anyVulkan = profile?.vulkan ?: false

        val candidates = buildList {
            when {
                mc.isLowerVer("1.17") -> {
                    add(GL4ESRenderer)
                    add(NGGL4ESRenderer)
                    // Not GL4ES based, so a crash in GL4ES' array handling does not follow
                    if (anyVulkan) add(KopperZinkRenderer)
                    add(MobileGluesRenderer)
                }
                shaders || !mc.isLowerVer("1.21.5") -> {
                    add(MobileGluesRenderer)
                    if (goodVulkan) add(KopperZinkRenderer)
                    add(NGGL4ESRenderer)
                }
                else -> {
                    add(NGGL4ESRenderer)
                    add(MobileGluesRenderer)
                }
            }
            if (goodVulkan) add(KopperZinkRenderer)
            add(MobileGluesRenderer)
            add(NGGL4ESRenderer)
            if (anyVulkan) add(KopperZinkRenderer)
        }
        val available = Renderers.getRenderers()
        // Render mods that break the GL4ES family keep running: pick another renderer for them
        val avoidGl4es = MobileModRules.avoidGl4es(version)
        val usable = candidates.distinct().filter { renderer ->
            !(avoidGl4es && MobileModRules.isGl4esFamily(renderer)) &&
            available.any { it.getUniqueIdentifier() == renderer.getUniqueIdentifier() } &&
                    renderer.getMinMCVersion()?.let { mc.isLowerVer(it) } != true &&
                    renderer.getMaxMCVersion()?.let { mc.isBiggerVer(it) } != true
        }
        // Skip the renderers that already crashed natively on this version
        val crashed = crashedRenderers(version)
        val chosen = usable.firstOrNull { it.getUniqueIdentifier() !in crashed }
            ?: usable.firstOrNull()
            ?: candidates.first()
        if (usable.isNotEmpty() && usable.all { it.getUniqueIdentifier() in crashed }) {
            // Everything crashed once: start over instead of being stuck
            clearCrashes(version)
        }
        lastReason = "${version.getVersionName()} ($mc, shaders=$shaders, vulkan=$goodVulkan, skipped=${crashed.size}) -> ${chosen.getRendererName()}"
        Logger.info(TAG, lastReason)
        return chosen.getUniqueIdentifier()
    }

    /** Why the last choice was made, printed in the launch log */
    @Volatile var lastReason: String = ""
        private set

    private fun crashFile(version: Version) = File(version.getVersionPath(), "ona_crashed_renderers.txt")

    private fun crashedRenderers(version: Version): Set<String> =
        runCatching { crashFile(version).readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }.getOrDefault(emptySet())

    private fun clearCrashes(version: Version) {
        runCatching { crashFile(version).delete() }
    }

    /**
     * The game crashed inside the renderer or the driver: the next launch of this version with
     * ONA Smart moves on to the next renderer that suits it.
     */
    fun recordCrash(version: Version, rendererId: String) {
        if (rendererId == ID) return
        runCatching {
            val known = crashedRenderers(version)
            if (rendererId !in known) crashFile(version).appendText("$rendererId\n")
            Logger.info(TAG, "Marked $rendererId as crashing on ${version.getVersionName()}")
        }
    }
}
