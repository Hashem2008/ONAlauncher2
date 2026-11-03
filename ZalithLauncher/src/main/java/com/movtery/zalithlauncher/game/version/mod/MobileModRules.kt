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
 * ONA: makes render engine mods work with the renderers phones have.
 *
 * Mods that replace Minecraft's world renderer with desktop OpenGL tricks (shader generic
 * vertex attributes, constant attributes, persistent mapping, multi draw) leave vertex array
 * state behind under the GL4ES family (GL4ES, Krypton Wrapper), which maps generic
 * attributes onto its fixed function array slots; the next ordinary draw then reads past the
 * end of a buffer and the GPU driver crashes in memmove under glDrawArrays.
 *
 *  - Nothirium is made compatible: under a GL4ES renderer its config is switched to the GL15
 *    engine, plain client state arrays exactly like vanilla 1.12, which GL4ES handles well.
 *    The original setting comes back with any other renderer. Only if it still crashes with
 *    GL15 is it switched off.
 *  - Vintagium and Celeritas (Sodium ports that need modern GL) are switched off under a GL4ES
 *    renderer (renamed to *.jar.ona-off) and come back with any other renderer.
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

    /** Mods made compatible through their own config instead of being switched off */
    private const val NOTHIRIUM = "nothirium"
    private const val NOTHIRIUM_SAFE_ENGINE = "GL15"

    /** Written when Nothirium crashed even on the GL15 engine: then it is switched off */
    private fun nothiriumFailedMarker(version: Version) = File(version.getVersionPath(), "ona_nothirium_gl15_crashed")
    private fun nothiriumConfig(version: Version) = File(version.getGameDir(), "config/nothirium.cfg")
    /** The renderEngine value the user had before we changed it */
    private fun nothiriumBackup(version: Version) = File(version.getGameDir(), "config/nothirium.cfg.ona-original")

    private val ENGINE_LINE = Regex("^(\\s*S:renderEngine=)(\\S*)\\s*$", RegexOption.MULTILINE)

    /** Set Nothirium's render engine, keeping the user's value aside the first time */
    private fun setNothiriumEngine(version: Version, engine: String) {
        val cfg = nothiriumConfig(version)
        val backup = nothiriumBackup(version)
        val text = if (cfg.isFile) cfg.readText() else ""
        val current = ENGINE_LINE.find(text)?.groupValues?.get(2)
        if (current == engine) return
        if (!backup.exists()) backup.writeText(current ?: "AUTOMATIC")
        val updated = when {
            current != null -> ENGINE_LINE.replace(text) { it.groupValues[1] + engine }
            text.contains("general {") -> text.replaceFirst("general {", "general {\n    S:renderEngine=$engine")
            else -> text + "\ngeneral {\n    S:renderEngine=$engine\n}\n"
        }
        cfg.parentFile?.mkdirs()
        cfg.writeText(updated)
        Logger.info(TAG, "Nothirium render engine set to $engine (was ${current ?: "default"})")
    }

    /** Put back the user's Nothirium render engine */
    private fun restoreNothiriumEngine(version: Version) {
        val backup = nothiriumBackup(version)
        if (!backup.isFile) return
        val original = backup.readText().trim().ifEmpty { "AUTOMATIC" }
        val cfg = nothiriumConfig(version)
        if (cfg.isFile) {
            val text = cfg.readText()
            if (ENGINE_LINE.containsMatchIn(text)) cfg.writeText(ENGINE_LINE.replace(text) { it.groupValues[1] + original })
        }
        backup.delete()
    }

    /**
     * The game crashed in the renderer: if Nothirium ran on the GL15 engine we set, it is not
     * enough for this version and the next launch switches it off instead.
     */
    fun onRendererCrash(version: Version) {
        runCatching {
            if (nothiriumBackup(version).isFile && riskyMods(version.modsDir()).contains(NOTHIRIUM)) {
                nothiriumFailedMarker(version).writeText("1")
                Logger.info(TAG, "Nothirium crashed on GL15 too, it will be switched off")
            }
        }
    }

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

    /** What [apply] did, for the toast */
    class Result(val switchedOff: List<String>, val madeCompatible: List<String>)

    /**
     * Make risky render mods safe for a GL4ES based renderer, undo it for any other.
     * Never leaves a mod off that another mod needs.
     */
    fun apply(version: Version, renderer: RendererInterface): Result {
        val modsDir = version.modsDir()
        if (!modsDir.isDirectory) return Result(emptyList(), emptyList())
        if (!isGl4esFamily(renderer)) {
            restore(modsDir)
            runCatching { restoreNothiriumEngine(version) }
            return Result(emptyList(), emptyList())
        }
        // Nothirium: GL15 engine first, off only if that crashed too
        val compatible = mutableListOf<String>()
        val nothiriumFailed = nothiriumFailedMarker(version).exists()
        if (!nothiriumFailed && riskyMods(modsDir).contains(NOTHIRIUM)) {
            runCatching {
                setNothiriumEngine(version, NOTHIRIUM_SAFE_ENGINE)
                compatible.add("Nothirium (GL15)")
            }.onFailure { Logger.warning(TAG, "Could not configure Nothirium", it) }
        }

        val disabled = mutableListOf<String>()
        modsDir.listFiles { file -> file.isFile && file.name.endsWith(".jar") }?.forEach { jar ->
            val id = riskyId(jar) ?: return@forEach
            if (id == NOTHIRIUM && !nothiriumFailed) return@forEach
            val off = File(jar.path + OFF_SUFFIX)
            if (jar.renameTo(off)) disabled.add(jar.nameWithoutExtension)
        }
        if (disabled.isEmpty()) return Result(disabled, compatible)

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
                return Result(emptyList(), compatible)
            }
        }
        Logger.info(TAG, "Switched off for ${renderer.getRendererName()}: $disabled")
        return Result(disabled, compatible)
    }

    private fun restore(modsDir: File) {
        modsDir.listFiles { file -> file.isFile && file.name.endsWith(".jar$OFF_SUFFIX") }?.forEach { off ->
            val jar = File(off.path.removeSuffix(OFF_SUFFIX))
            if (!jar.exists()) off.renameTo(jar)
        }
    }
}
