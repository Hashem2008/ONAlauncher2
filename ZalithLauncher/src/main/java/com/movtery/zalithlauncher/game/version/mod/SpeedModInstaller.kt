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
 * ONA: speed mods for every modded version (Fabric, Quilt, Forge, NeoForge), also one whose
 * mods folder is still empty. A version without a rendering optimiser draws with Minecraft's own
 * renderer, several times slower on phone GPUs.
 *
 * - Sodium / Embeddium: rendering (never next to OptiFine or another optimiser)
 * - FerriteCore: memory, fewer garbage collector pauses
 * - Lithium: game logic and the world thread ("Can't keep up"), only in small mod setups
 *
 * Every jar is checked against the loader and game version through its metadata ([ModCompat]),
 * so nothing that would not load is kept. Safety: a mod ONA added that shows up in a loading
 * crash is removed and never added to that version again; one the player removed by hand is not
 * added back. A mod with no fitting build is asked again only after a few days.
 * Setting "onaAutoSpeedMod" (default on).
 */
object SpeedModInstaller {
    private const val TAG = "SpeedModInstaller"
    private const val CHECK_FILE = "ona_speed_mod_checked.txt"
    private const val ADDED_FILE = "ona_speed_mods_added.txt"
    private const val BLOCKED_FILE = "ona_speed_mods_blocked.txt"
    private const val RETRY_MS = 3L * 24 * 60 * 60 * 1000

    /** Rendering optimisers and mods that do not go together with one */
    private val RENDER_OPTIMISERS = listOf(
        "sodium", "embeddium", "rubidium", "xenon", "magnesium", "optifine", "optifabric",
        "nvidium", "celeritas", "vintagium", "relictium", "canvas"
    )

    private class SpeedMod(
        val slug: String,
        /** Mod id the jar must provide */
        val modId: String,
        val loaders: Set<String>,
        /** Jar names (lower case) that mean it is there already or must not be added */
        val conflicts: List<String>,
        /** Package names / ids that point at this mod in a crash */
        val crashMarkers: List<String>,
        /** Only added when the version has at most this many mods of its own */
        val maxMods: Int = Int.MAX_VALUE,
        val firstMc: String = "1.16"
    )

    private val ALL_LOADERS = setOf("fabric", "quilt", "forge", "neoforge")

    private fun rendererMods(loader: String): List<SpeedMod> {
        val markers = listOf("sodium", "embeddium")
        return when (loader) {
            "fabric", "quilt" -> listOf(SpeedMod("sodium", "sodium", ALL_LOADERS, RENDER_OPTIMISERS, markers))
            "neoforge" -> listOf(
                SpeedMod("sodium", "sodium", ALL_LOADERS, RENDER_OPTIMISERS, markers),
                SpeedMod("embeddium", "embeddium", ALL_LOADERS, RENDER_OPTIMISERS, markers)
            )
            "forge" -> listOf(SpeedMod("embeddium", "embeddium", ALL_LOADERS, RENDER_OPTIMISERS, markers))
            else -> emptyList()
        }
    }

    private val EXTRA_MODS = listOf(
        SpeedMod(
            "ferritecore", "ferritecore", ALL_LOADERS,
            conflicts = listOf("ferritecore", "hydrogen"),
            crashMarkers = listOf("ferritecore")
        ),
        SpeedMod(
            "lithium", "lithium", setOf("fabric", "quilt", "neoforge"),
            conflicts = listOf("lithium", "canary", "radium", "roadrunner", "hydrogen", "alternate-current"),
            crashMarkers = listOf("mods.lithium", "lithium"),
            maxMods = 25
        )
    )

    /** Set by [ensure]: no rendering optimiser exists for this loader and game version */
    @Volatile
    var noRendererFor: String? = null
        private set

    /** @return names of the jars added this time (empty when nothing was added) */
    fun ensure(version: Version): List<String> {
        noRendererFor = null
        if (!AllSettings.autoSpeedMod.getValue()) return emptyList()
        val environment = version.modEnvironment() ?: return emptyList()
        val loader = environment.loader ?: return emptyList()
        val mc = environment.gameVersion ?: return emptyList()
        val modsDir = version.modsDir()
        val versionDir = version.getVersionPath()

        val added = readLines(File(versionDir, ADDED_FILE)).toMutableSet()
        val blocked = readLines(File(versionDir, BLOCKED_FILE)).toMutableSet()
        val jars = modsDir.listFiles { file -> file.name.endsWith(".jar") }.orEmpty()
        val jarNames = jars.map { it.name }.toSet()

        // A jar ONA added that is gone now was removed by the player: keep it out of this version
        added.filter { it.substringAfter('|') !in jarNames }.forEach { entry ->
            blocked.add(entry.substringBefore('|'))
            added.remove(entry)
            Logger.info(TAG, "${entry.substringAfter('|')} was removed by the player, not added again")
        }
        val ownMods = jarNames.count { name -> added.none { it.endsWith("|$name") } }

        val checked = readLines(File(versionDir, CHECK_FILE))
            .mapNotNull { line -> line.split('=').takeIf { it.size == 2 }?.let { it[0] to (it[1].toLongOrNull() ?: 0L) } }
            .toMap().toMutableMap()
        val now = System.currentTimeMillis()
        val installedNow = mutableListOf<String>()

        fun present(mod: SpeedMod) = jars.any { jar -> mod.conflicts.any { jar.name.lowercase().contains(it) } }

        /** @return true when a jar was added */
        fun tryInstall(mod: SpeedMod): Boolean? {
            if (mod.slug in blocked || loader !in mod.loaders || mc.isLowerVer(mod.firstMc)) return null
            if (ownMods > mod.maxMods || present(mod)) return null
            if (now - (checked[mod.slug] ?: 0L) < RETRY_MS) return null
            modsDir.mkdirs()
            var networkError = false
            val installed = runCatching { ModCompat.installCompatible(mod.slug, modsDir, environment, mod.modId) }
                .onFailure {
                    networkError = true
                    Logger.warning(TAG, "${mod.slug} for ${version.getVersionName()} failed", it)
                }
                .getOrNull()
            return if (installed != null) {
                Logger.info(TAG, "${installed.name} added to ${version.getVersionName()} ($loader $mc)")
                added.add("${mod.slug}|${installed.name}")
                checked.remove(mod.slug)
                installedNow.add(installed.name)
                true
            } else {
                // offline: try again on the next launch; no build: only after a few days
                if (!networkError) checked[mod.slug] = now
                false
            }
        }

        val renderers = rendererMods(loader)
        if (renderers.isNotEmpty() && renderers.none { present(it) }) {
            var rendererAdded = false
            var tried = false
            for (mod in renderers) {
                val result = tryInstall(mod) ?: continue
                tried = true
                if (result) {
                    rendererAdded = true
                    break
                }
            }
            if (tried && !rendererAdded && renderers.all { checked.containsKey(it.slug) }) {
                noRendererFor = "$loader $mc"
                Logger.info(TAG, "No rendering optimiser fits ${version.getVersionName()} ($loader $mc)")
            }
        } else if (renderers.isEmpty()) {
            Logger.info(TAG, "No rendering optimiser for loader $loader")
        }
        EXTRA_MODS.forEach { tryInstall(it) }

        runCatching {
            writeLines(File(versionDir, ADDED_FILE), added)
            writeLines(File(versionDir, BLOCKED_FILE), blocked)
            writeLines(File(versionDir, CHECK_FILE), checked.map { (slug, time) -> "$slug=$time" })
        }.onFailure { Logger.warning(TAG, "Could not save the speed mod state", it) }
        return installedNow
    }

    /**
     * The game crashed: a mod ONA added that appears in a loading / mixin failure is removed and
     * never added to this version again (only ONA's own additions, never the player's mods).
     * @return the removed jar, or null
     */
    fun onCrash(version: Version, crashText: String): String? = runCatching {
        if (crashText.isBlank()) return@runCatching null
        val versionDir = version.getVersionPath()
        val addedFile = File(versionDir, ADDED_FILE)
        val added = readLines(addedFile).toMutableSet()
        if (added.isEmpty()) return@runCatching null
        val text = crashText.lowercase()
        val loadingFailure = LOADING_FAILURE.any { text.contains(it) }
        if (!loadingFailure) return@runCatching null
        val all = rendererMods("fabric") + rendererMods("forge") + EXTRA_MODS
        for (entry in added.toList()) {
            val slug = entry.substringBefore('|')
            val jarName = entry.substringAfter('|')
            val mod = all.firstOrNull { it.slug == slug } ?: continue
            val named = text.contains(jarName.lowercase()) || mod.crashMarkers.any { marker ->
                Regex("""(mod|mixin[^\n]{0,80})\W+${Regex.escape(marker)}\b|${Regex.escape(marker)}\.mixins?\.json|${Regex.escape(marker)}[^\n]{0,40}(failed|incompatible|requires)""")
                    .containsMatchIn(text)
            }
            if (!named) continue
            File(version.modsDir(), jarName).delete()
            added.remove(entry)
            val blocked = readLines(File(versionDir, BLOCKED_FILE)).toMutableSet().apply { add(slug) }
            writeLines(File(versionDir, BLOCKED_FILE), blocked)
            writeLines(addedFile, added)
            Logger.warning(TAG, "$jarName (added by ONA) failed to load in ${version.getVersionName()}, removed")
            return@runCatching jarName
        }
        null
    }.onFailure { Logger.warning(TAG, "Crash check failed", it) }.getOrNull()

    private val LOADING_FAILURE = listOf(
        "mixin apply", "mixinapplyerror", "mixin transformation", "invalidmixinexception",
        "modloadingexception", "mod loading has failed", "incompatible mods found", "incompatible mod set",
        "noclassdeffounderror", "nosuchmethoderror", "nosuchfielderror", "classnotfoundexception",
        "error loading mods", "failed to load mod", "requires mod", "critical injection failure"
    )

    private fun readLines(file: File): List<String> =
        runCatching { file.readLines().map { it.trim() }.filter { it.isNotEmpty() } }.getOrDefault(emptyList())

    private fun writeLines(file: File, lines: Collection<String>) {
        if (lines.isEmpty()) file.delete() else file.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
