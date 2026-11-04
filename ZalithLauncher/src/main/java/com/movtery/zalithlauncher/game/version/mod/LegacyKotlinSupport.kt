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
import com.movtery.zalithlauncher.ui.screens.content.elements.modEnvironment
import com.movtery.zalithlauncher.ui.screens.content.elements.modsDir
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File
import java.util.zip.ZipFile

/**
 * ONA: Forge 1.12 and older mods written in Kotlin (Better Foliage and others) need the Kotlin
 * runtime from Forgelin, but they only ask for it in their code, not in mcmod.info, so the normal
 * requirement check cannot see it and the game dies at start with
 * "NoClassDefFoundError: kotlin/...". When a Kotlin mod is there and no jar carries the Kotlin
 * runtime, Forgelin-Continuous (the maintained Forgelin, same mod id) is installed from Modrinth.
 */
object LegacyKotlinSupport {
    private const val TAG = "LegacyKotlinSupport"
    private const val FORGELIN = "forgelin-continuous"

    /** @return the installed jar name, or null when nothing was needed or it could not be installed */
    fun ensure(version: Version): String? {
        lastMissing = null
        val environment = version.modEnvironment() ?: return null
        if (environment.loader != "forge" || !environment.gameVersion.isLowerVer("1.13")) return null
        val jars = version.modsDir().listFiles { file -> file.isFile && file.name.endsWith(".jar") } ?: return null
        var kotlinMod: String? = null
        for (jar in jars) {
            when (kotlinKind(jar)) {
                Kind.RUNTIME -> return null
                Kind.MOD -> if (kotlinMod == null) kotlinMod = jar.name
                Kind.NONE -> {}
            }
        }
        if (kotlinMod == null) return null
        lastMissing = kotlinMod
        return runCatching { ModCompat.installCompatible(FORGELIN, version.modsDir(), environment, null) }
            .onFailure { Logger.warning(TAG, "Could not install Forgelin for $kotlinMod", it) }
            .getOrNull()
            ?.name
            ?.also {
                lastMissing = null
                Logger.info(TAG, "$kotlinMod needs the Kotlin runtime, installed $it")
            }
    }

    /** The Kotlin mod still without its runtime after the last [ensure] (no internet...), or null */
    @Volatile
    var lastMissing: String? = null
        private set

    private enum class Kind { NONE, MOD, RUNTIME }

    /** Kotlin mods known to need Forgelin, by jar name, for builds without a .kotlin_module */
    private val KNOWN_KOTLIN_MODS = listOf("betterfoliage", "better-foliage", "better_foliage")

    /**
     * Reads only the zip directory, no class is unpacked. A jar only counts as the Kotlin runtime
     * when Forge loads it early (a coremod or Forgelin itself) and it has the whole stdlib, reflect
     * part included: a normal mod that shades a few Kotlin classes is loaded too late for Kotlin
     * coremods like Better Foliage, which then die with kotlin/reflect/KDeclarationContainer.
     */
    private fun kotlinKind(jar: File): Kind = runCatching {
        ZipFile(jar).use { zip ->
            var compiledWithKotlin = false
            var hasUnit = false
            var hasReflect = false
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                when {
                    name == "kotlin/Unit.class" -> hasUnit = true
                    name == "kotlin/reflect/KDeclarationContainer.class" -> hasReflect = true
                    name.startsWith("META-INF/") && name.endsWith(".kotlin_module") -> compiledWithKotlin = true
                }
            }
            val lowerName = jar.name.lowercase()
            val loadedEarly = lowerName.contains("forgelin") || isCoreMod(zip)
            when {
                hasUnit && hasReflect && loadedEarly -> Kind.RUNTIME
                compiledWithKotlin || KNOWN_KOTLIN_MODS.any { lowerName.contains(it) } -> Kind.MOD
                else -> Kind.NONE
            }
        }
    }.getOrDefault(Kind.NONE)

    private fun isCoreMod(zip: ZipFile): Boolean = runCatching {
        val manifest = zip.getEntry("META-INF/MANIFEST.MF") ?: return@runCatching false
        val text = zip.getInputStream(manifest).bufferedReader().use { it.readText() }
        text.contains("FMLCorePlugin:") || text.contains("TweakClass:")
    }.getOrDefault(false)
}
