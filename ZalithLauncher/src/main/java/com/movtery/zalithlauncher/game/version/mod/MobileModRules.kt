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

import com.movtery.zalithlauncher.game.renderer.RendererInterface
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.ui.screens.content.elements.modEnvironment
import com.movtery.zalithlauncher.ui.screens.content.elements.modsDir
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File

/**
 * ONA: mods that replace Minecraft's own world renderer with desktop OpenGL tricks (their own
 * vertex buffers, attribute layouts, persistent mapping, multi draw). Under the GL4ES family
 * of renderers (GL4ES, Krypton Wrapper), which translate old desktop GL to GLES, they leave
 * vertex array state behind that the next ordinary draw reads past the end of, and the GPU
 * driver crashes in a memory copy (SIGSEGV in memmove under glDrawArrays).
 *
 * They are optional optimisation mods, so while such a renderer is in use they are switched
 * off (renamed to *.jar.ona-off), and switched back on with any other renderer.
 */
object MobileModRules {
    private const val TAG = "MobileModRules"
    const val OFF_SUFFIX = ".ona-off"

    /** Mod ids, and file name hints for jars without readable metadata */
    private val GL4ES_UNSAFE = mapOf(
        "nothirium" to "nothirium",
        "vintagium" to "vintagium",
        "celeritas" to "celeritas"
    )

    /** Renderer ids that are GL4ES based */
    private val GL4ES_FAMILY = setOf("opengles2", "opengles3")

    fun isGl4esFamily(renderer: RendererInterface): Boolean = renderer.getRendererId() in GL4ES_FAMILY

    /** Mods in this folder that are known to crash GL4ES based renderers */
    fun riskyMods(modsDir: File): List<String> =
        modsDir.listFiles { file -> file.isFile && file.name.endsWith(".jar") }
            ?.mapNotNull { jar -> riskyId(jar) }
            .orEmpty()

    private fun riskyId(jar: File): String? {
        val name = jar.name.lowercase()
        GL4ES_UNSAFE.forEach { (id, hint) -> if (name.contains(hint)) return id }
        val info = runCatching { ModCompat.read(jar) }.getOrNull() ?: return null
        return GL4ES_UNSAFE.keys.firstOrNull { it in info.providedIds }
    }

    /**
     * Switch risky mods off for a GL4ES based renderer, back on for any other.
     * Never leaves a mod off that another mod needs.
     * @return display names of the mods that were switched off
     */
    fun apply(version: Version, renderer: RendererInterface): List<String> {
        val modsDir = version.modsDir()
        if (!modsDir.isDirectory) return emptyList()
        if (!isGl4esFamily(renderer)) {
            restore(modsDir)
            return emptyList()
        }
        val disabled = mutableListOf<String>()
        modsDir.listFiles { file -> file.isFile && file.name.endsWith(".jar") }?.forEach { jar ->
            if (riskyId(jar) == null) return@forEach
            val off = File(jar.path + OFF_SUFFIX)
            if (jar.renameTo(off)) disabled.add(jar.nameWithoutExtension)
        }
        if (disabled.isEmpty()) return disabled

        // Another mod may hard depend on one of them: then it has to stay
        val environment = version.modEnvironment()
        if (environment != null) {
            val missing = runCatching { ModCompat.analyze(modsDir, environment) }.getOrDefault(emptyList())
                .filter { it.kind == ModCompat.PROBLEM_MISSING }
                .map { it.requiredId }
                .toSet()
            if (missing.any { it in GL4ES_UNSAFE.keys }) {
                restore(modsDir)
                Logger.info(TAG, "Kept risky render mods, other mods need them")
                return emptyList()
            }
        }
        Logger.info(TAG, "Switched off for ${renderer.getRendererName()}: $disabled")
        return disabled
    }

    private fun restore(modsDir: File) {
        modsDir.listFiles { file -> file.isFile && file.name.endsWith(".jar$OFF_SUFFIX") }?.forEach { off ->
            val jar = File(off.path.removeSuffix(OFF_SUFFIX))
            if (!jar.exists()) off.renameTo(jar)
        }
    }
}
