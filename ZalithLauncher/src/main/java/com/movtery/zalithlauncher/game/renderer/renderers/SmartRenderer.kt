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
        val mc = version.getVersionInfo()?.minecraftVersion ?: return NGGL4ESRenderer.getUniqueIdentifier()
        val gameDir = version.getGameDir()
        val modNames = File(gameDir, "mods").list()?.filter { it.endsWith(".jar") }?.map { it.lowercase() }.orEmpty()
        val shaderMod = modNames.any { name -> SHADER_MODS.any { name.contains(it) } }
        val shaderPacks = File(gameDir, "shaderpacks").listFiles()?.any { !it.name.endsWith(".txt") } == true
        val shaders = shaderMod || shaderPacks && modNames.any { name -> SODIUM_LIKE.any { name.contains(it) } }
        val profile = DeviceProfiler.peek()
        val goodVulkan = profile?.let { it.vulkan && it.turnipRecommended } ?: false

        val candidates = buildList {
            when {
                mc.isLowerVer("1.17") -> {
                    add(GL4ESRenderer)
                    add(NGGL4ESRenderer)
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
        }
        val available = Renderers.getRenderers()
        val chosen = candidates.firstOrNull { renderer ->
            available.any { it.getUniqueIdentifier() == renderer.getUniqueIdentifier() } &&
                    renderer.getMinMCVersion()?.let { mc.isLowerVer(it) } != true &&
                    renderer.getMaxMCVersion()?.let { mc.isBiggerVer(it) } != true
        } ?: candidates.first()
        Logger.info(TAG, "${version.getVersionName()} ($mc, shaders=$shaders, vulkan=$goodVulkan) -> ${chosen.getRendererName()}")
        return chosen.getUniqueIdentifier()
    }
}
