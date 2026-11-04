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
 * Mods are never switched off or deleted:
 *  - Nothirium is made compatible: under a GL4ES renderer its config is switched to the GL15
 *    engine, plain client state arrays exactly like vanilla 1.12, which GL4ES handles well.
 *    The original setting comes back with any other renderer. If it still crashes on GL15,
 *    ONA Smart moves the version to Kopper Zink / MobileGlues.
 *  - Vintagium and Celeritas (Sodium ports that need modern GL) make ONA Smart pick Kopper
 *    Zink / MobileGlues for the version instead of a GL4ES renderer.
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
     * The game crashed in the renderer: if Nothirium ran on the GL15 engine we set, that is not
     * enough for this version and ONA Smart stops using GL4ES renderers for it.
     */
    fun onRendererCrash(version: Version) {
        runCatching {
            if (nothiriumBackup(version).isFile && riskyMods(version.modsDir()).contains(NOTHIRIUM)) {
                nothiriumFailedMarker(version).writeText("1")
                Logger.info(TAG, "Nothirium crashed on GL15 too, GL4ES renderers will be avoided")
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

    /** By file name only: reading 500 jars on every launch made launching slow */
    private fun riskyId(jar: File): String? {
        val name = jar.name.lowercase()
        GL4ES_UNSAFE.forEach { (id, hint) -> if (name.contains(hint)) return id }
        return null
    }

    /** What [apply] did, for the toast */
    class Result(val switchedOff: List<String>, val madeCompatible: List<String>)

    /**
     * The GL4ES family should not run this version: it has a Sodium port that needs modern GL
     * (Vintagium, Celeritas), or Nothirium crashed there even on its GL15 engine. ONA Smart
     * then starts it with Kopper Zink or MobileGlues instead, and every mod stays on.
     */
    fun avoidGl4es(version: Version): Boolean {
        // Nothirium's chunk renderer flickers and leaves holes in the world under GL4ES even on
        // its GL15 engine, so prefer a renderer with real desktop GL for it as well
        return runCatching { riskyMods(version.modsDir()) }.getOrDefault(emptyList()).isNotEmpty()
    }

    /**
     * Mods that compile GLSL shaders of their own (Blood Magic and others in old packs). GL4ES'
     * shader converter can half convert such a shader (VGPU "gl_TexCoord ... MaxTextureCoords (0)")
     * and leave the GL state broken: white hotbar and bars, text as black squares (Ultra Realisim,
     * 1.12.2, Adreno 840). Krypton Wrapper rejects the same shader cleanly, the mod falls back to
     * its plain path and the picture is right (checked on the same pack: correct HUD, 140-177 FPS).
     * @return names of the mod jars that bring their own shaders
     */
    fun modsWithOwnShaders(version: Version): List<String> {
        val jars = version.modsDir().listFiles { file -> file.isFile && file.name.endsWith(".jar") }.orEmpty()
        return jars.filter { jar ->
            runCatching {
                java.util.zip.ZipFile(jar).use { zip ->
                    zip.entries().asSequence().any { entry ->
                        val name = entry.name.lowercase()
                        name.startsWith("assets/") && !name.startsWith("assets/minecraft/") &&
                            SHADER_EXTENSIONS.any { name.endsWith(it) }
                    }
                }
            }.getOrDefault(false)
        }.map { it.name }
    }

    private val SHADER_EXTENSIONS = listOf(".vsh", ".fsh", ".vert", ".frag", ".glsl")

    /**
     * Make risky render mods work with the renderer. Mods are never switched off or deleted:
     * Nothirium gets its GL15 engine under a GL4ES renderer, the others are handled by the
     * renderer choice ([avoidGl4es]). Jars an older ONA build switched off are put back.
     */
    fun apply(version: Version, renderer: RendererInterface): Result {
        val modsDir = version.modsDir()
        if (!modsDir.isDirectory) return Result(emptyList(), emptyList())
        restore(modsDir)
        if (!isGl4esFamily(renderer)) {
            runCatching { restoreNothiriumEngine(version) }
            return Result(emptyList(), emptyList())
        }
        val compatible = mutableListOf<String>()
        if (riskyMods(modsDir).contains(NOTHIRIUM)) {
            runCatching {
                setNothiriumEngine(version, NOTHIRIUM_SAFE_ENGINE)
                compatible.add("Nothirium (GL15)")
            }.onFailure { Logger.warning(TAG, "Could not configure Nothirium", it) }
        }
        return Result(emptyList(), compatible)
    }

    private fun restore(modsDir: File) {
        modsDir.listFiles { file -> file.isFile && file.name.endsWith(".jar$OFF_SUFFIX") }?.forEach { off ->
            val jar = File(off.path.removeSuffix(OFF_SUFFIX))
            if (!jar.exists()) off.renameTo(jar)
        }
    }
}
