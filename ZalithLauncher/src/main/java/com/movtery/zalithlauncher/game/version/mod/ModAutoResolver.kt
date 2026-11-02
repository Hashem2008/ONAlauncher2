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

import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.coroutine.Task
import com.movtery.zalithlauncher.coroutine.TaskSystem
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformClasses
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.utils.isLowerVer
import com.movtery.zalithlauncher.ui.androidText
import com.movtery.zalithlauncher.ui.screens.content.elements.modEnvironment
import com.movtery.zalithlauncher.ui.screens.content.elements.modsDir
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * ONA: installs what the mods of a version need without asking. Reads the real jar metadata
 * (fabric.mod.json / mods.toml / neoforge.mods.toml, nested jars included), downloads every
 * missing required mod from Modrinth and swaps builds made for another game or loader version.
 * It never disables a mod, whatever it cannot fix is left for the user to decide.
 */
object ModAutoResolver {
    private const val TAG = "ModAutoResolver"

    /** New downloads can bring their own missing dependencies, so go a few rounds */
    private const val MAX_ROUNDS = 4

    class Result(val fixed: Int, val remaining: List<ModCompat.Problem>)

    /**
     * Blocking and network heavy, call it off the main thread.
     * @param onProgress the mod currently being handled
     */
    fun resolve(version: Version, onProgress: (String) -> Unit = {}): Result {
        val environment = version.modEnvironment() ?: return Result(0, emptyList())
        val modsDir = version.modsDir()
        if (!modsDir.isDirectory) return Result(0, emptyList())

        var remaining = runCatching { ModCompat.analyze(modsDir, environment) }.getOrElse {
            Logger.warning(TAG, "Could not read the mods of ${version.getVersionName()}", it)
            return Result(0, emptyList())
        }
        if (remaining.isEmpty()) return Result(0, remaining)
        if (!modrinthReachable()) {
            Logger.warning(TAG, "Modrinth is not reachable, leaving ${remaining.size} problem(s) to the user")
            return Result(0, remaining)
        }
        var fixed = 0
        for (round in 0 until MAX_ROUNDS) {
            if (remaining.isEmpty()) break
            var fixedThisRound = 0
            remaining.forEach { problem ->
                onProgress(if (problem.kind == ModCompat.PROBLEM_MISSING) problem.requiredId else problem.modName)
                val result = ModCompat.fix(problem, modsDir, environment, false)
                if (result != ModCompat.FIX_FAILED) fixedThisRound++
            }
            fixed += fixedThisRound
            remaining = runCatching { ModCompat.analyze(modsDir, environment) }.getOrDefault(remaining)
            if (fixedThisRound == 0) break
        }
        Logger.info(TAG, "${version.getVersionName()}: fixed $fixed, ${remaining.size} left")
        return Result(fixed, remaining)
    }

    /** Quick check so an offline launch never waits on a dozen network timeouts */
    private fun modrinthReachable(): Boolean = runCatching {
        val connection = URL("https://api.modrinth.com/v2").openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        try {
            connection.responseCode in 200..499
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    /** Mod ids that load shader packs */
    private val SHADER_LOADERS = setOf("iris", "oculus", "optifine", "optifabric")

    /**
     * Shader packs do nothing without a shader loader, install Iris (Fabric, Quilt, NeoForge
     * 1.20.6+) or Oculus (Forge) when the version has none. Their own requirements (Sodium,
     * Embeddium...) are picked up by [resolve] afterwards.
     * @return true when a loader was installed
     */
    fun ensureShaderLoader(version: Version): Boolean {
        val environment = version.modEnvironment() ?: return false
        val modsDir = version.modsDir()
        val installed = modsDir.listFiles { file -> file.isFile && file.name.endsWith(".jar") }
            ?.mapNotNull { ModCompat.read(it) }
            ?.any { info -> info.providedIds.any { it in SHADER_LOADERS } }
            ?: false
        if (installed) return false
        val candidates = when (environment.loader) {
            "forge" -> listOf("oculus")
            "neoforge" -> if (environment.gameVersion.isLowerVer("1.20.6")) listOf("oculus") else listOf("iris")
            else -> listOf("iris")
        }
        if (!modsDir.isDirectory && !modsDir.mkdirs()) return false
        return candidates.any { slug ->
            runCatching { ModCompat.installCompatible(slug, modsDir, environment, null) != null }
                .onFailure { Logger.warning(TAG, "Could not install $slug", it) }
                .getOrDefault(false)
        }.also { if (it) Logger.info(TAG, "Installed a shader loader for ${version.getVersionName()}") }
    }

    /**
     * Called after anything was installed into a version from the download screens: mods get
     * their requirements, shader packs get a shader loader and its requirements.
     */
    fun afterInstall(classes: PlatformClasses, versions: List<Version>) {
        when (classes) {
            PlatformClasses.MOD -> versions.forEach { scheduleAfterDownloads(it) }
            PlatformClasses.SHADERS -> versions.forEach { scheduleAfterDownloads(it, shaders = true) }
            else -> {}
        }
    }

    /**
     * Run [resolve] as a background task once the downloads that are running right now are done,
     * so it never races the platform dependency downloader for the same file.
     */
    fun scheduleAfterDownloads(version: Version, shaders: Boolean = false) {
        if (version.modEnvironment() == null) return
        val id = "ona-autodeps/${version.getVersionName()}"
        if (TaskSystem.containsTask(id)) return
        TaskSystem.submitTask(
            Task.runTask(
                id = id,
                task = { task ->
                    task.updateProgress(-1f)
                    task.updateMessage(androidText(R.string.mod_auto_deps_waiting))
                    // Give the download that triggered us time to register its dependency tasks
                    delay(800.milliseconds)
                    withTimeoutOrNull(15.minutes) {
                        TaskSystem.tasksFlow.first { tasks -> tasks.none { it.id != id } }
                    }
                    if (shaders) {
                        task.updateMessage(androidText(R.string.mod_auto_deps_installing, "Iris / Oculus"))
                        ensureShaderLoader(version)
                    }
                    resolve(version) { name ->
                        task.updateMessage(androidText(R.string.mod_auto_deps_installing, name))
                    }
                }
            )
        )
    }
}
