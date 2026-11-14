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

package com.movtery.zalithlauncher.game.download.modpack.update

import com.movtery.zalithlauncher.game.download.assets.platform.Platform
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformClasses
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformReleaseType
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformSearchFilter
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformVersion
import com.movtery.zalithlauncher.game.download.assets.platform.getProjectByVersion
import com.movtery.zalithlauncher.game.download.assets.platform.getVersions
import com.movtery.zalithlauncher.game.download.assets.platform.mirroredCurseForgeSource
import com.movtery.zalithlauncher.game.download.assets.platform.mirroredModrinthSource
import com.movtery.zalithlauncher.game.download.assets.platform.mirroredPlatformSearcher
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * ONA: "Update modpack". Finds the newest release of the pack a version was installed from
 * (CurseForge or Modrinth), and after the new release was installed as a NEW version, moves the
 * player's things over (worlds, settings, server list, screenshots, packs, mods the player added).
 * The old version is never changed or deleted: if anything goes wrong it still plays as before.
 */
object PackUpdater {
    private const val TAG = "PackUpdater"

    class Check(
        val source: PackSource,
        val platform: Platform,
        val projectId: String,
        val title: String,
        val iconUrl: String?,
        val latest: PlatformVersion,
        /** pack version the player has now, "" when unknown */
        val installed: String,
        /** true / false, null = could not tell */
        val upToDate: Boolean?
    ) {
        val latestLabel: String get() = latest.platformDisplayName()
        val latestMinecraft: String get() = latest.platformGameVersion().joinToString(", ")
    }

    class Migration(
        val worlds: Int,
        val settings: Int,
        val mods: Int,
        val packs: Int,
        val other: Int,
        /** the old version has no pack record: its extra mods could not be told apart */
        val modsUnknown: Boolean
    )

    private val VERSION_TOKEN = Regex("""\d+(?:\.\d+)+[a-z]?""", RegexOption.IGNORE_CASE)

    private fun tokens(text: String): Set<String> =
        VERSION_TOKEN.findAll(text.lowercase()).map { it.value.trimStart('v') }.toSet()

    private fun simple(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Finds the pack and its newest release. Throws with a readable message when the pack cannot
     * be found or the network fails.
     */
    suspend fun check(version: Version): Check = withContext(Dispatchers.IO) {
        val gameDir = version.getGameDir()
        val mc = version.getVersionInfo()?.minecraftVersion ?: ""
        val recorded = PackSources.read(gameDir) ?: PackSources.read(version.getVersionPath())
        val source = recorded ?: PackSource(name = version.getVersionName(), minecraft = mc)

        val recordedPlatform = source.platform?.let { p -> Platform.entries.firstOrNull { it.name == p } }
        val (platform, projectId, title, iconUrl) = run {
            val known = source.projectId
            if (known != null && recordedPlatform != null) {
                val project = runCatching { getProjectByVersion(known, recordedPlatform, printLog = false) }.getOrNull()
                return@run Found(recordedPlatform, known, project?.platformTitle() ?: source.name, project?.platformIconUrl())
            }
            val order = listOfNotNull(recordedPlatform) + Platform.entries.filter { it != recordedPlatform }
            order.firstNotNullOfOrNull { p ->
                runCatching { search(p, source.name, source.minecraft.ifEmpty { mc }) }
                    .onFailure { Logger.warning(TAG, "Search on ${p.name} failed for ${source.name}", it) }
                    .getOrNull()
            } ?: throw IOException("\"${source.name}\" was not found on CurseForge or Modrinth")
        }

        val all = getVersions(projectId, platform).filter { v ->
            runCatching { v.initFile(projectId) }.getOrDefault(false)
        }
        if (all.isEmpty()) throw IOException("$title has no downloadable files")
        val releases = all.filter { it.platformReleaseType() == PlatformReleaseType.RELEASE }
        val latest = (releases.ifEmpty { all }).maxBy { it.platformDatePublished() }

        // which pack version is installed now
        val latestTokens = tokens(latest.platformDisplayName() + " " + latest.platformVersion()) -
                latest.platformGameVersion().toSet()
        val upToDate: Boolean?
        val installed: String
        when {
            source.versionId != null && source.projectId == projectId -> {
                installed = source.version
                upToDate = source.versionId == latest.platformId()
            }
            source.version.isNotBlank() -> {
                installed = source.version
                upToDate = (tokens(source.version) intersect latestTokens).isNotEmpty() ||
                        simple(latest.platformVersion()) == simple(source.version)
            }
            else -> {
                // no record (installed before ONA kept one): look for the version in file names
                // and the main menu text the pack ships
                val hints = buildString {
                    gameDir.listFiles()?.forEach { append(it.name).append('\n') }
                    File(gameDir, "config/CustomMainMenu/mainmenu.json").takeIf { it.isFile && it.length() < 512_000 }
                        ?.let { append(runCatching { it.readText() }.getOrDefault("")) }
                }
                installed = ""
                upToDate = if (latestTokens.isNotEmpty() && latestTokens.all { it in tokens(hints) }) true else null
            }
        }

        // keep what was found, the next check is faster and knows the pack
        if (source.projectId == null || source.platform == null) {
            source.projectId = projectId
            source.platform = platform.name
            if (source.minecraft.isEmpty()) source.minecraft = mc
            if (source.version.isEmpty() && upToDate == true) source.version = latest.platformVersion()
            PackSources.write(gameDir, source)
        }

        Logger.info(TAG, "${version.getVersionName()}: pack $title on ${platform.name} ($projectId), installed \"$installed\", latest \"${latest.platformDisplayName()}\", up to date: $upToDate")
        Check(source, platform, projectId, title, iconUrl, latest, installed, upToDate)
    }

    private data class Found(val platform: Platform, val projectId: String, val title: String, val iconUrl: String?)

    private suspend fun search(platform: Platform, name: String, mc: String): Found? {
        val filter = PlatformSearchFilter(searchName = name, gameVersion = mc, limit = 20)
        val result = when (platform) {
            Platform.CURSEFORGE -> mirroredPlatformSearcher(mirroredCurseForgeSource(), printLog = false) {
                it.searchAssets(name, filter, PlatformClasses.MOD_PACK)
            }
            Platform.MODRINTH -> mirroredPlatformSearcher(mirroredModrinthSource(), printLog = false) {
                it.searchAssets(name, filter, PlatformClasses.MOD_PACK)
            }
        }
        val wanted = simple(name)
        val hit = result.getAssetsPage(PlatformClasses.MOD_PACK).data
            .map { it.first }
            .firstOrNull { simple(it.platformTitle()) == wanted }
            ?: return null
        return Found(platform, hit.platformId(), hit.platformTitle(), hit.platformIconUrl())
    }

    /** A free version name like "RLCraft 2.9.4". */
    fun newVersionName(versionsDir: File, base: String, label: String): String {
        val token = tokens(label).lastOrNull()
        val stem = base.replace(VERSION_TOKEN, "").replace(Regex("""\s+"""), " ").trim().ifEmpty { base }
        val wanted = (if (token != null) "$stem $token" else "$stem new")
            .replace(Regex("""[\\/:*?"<>|]"""), "").trim()
        var name = wanted
        var n = 2
        while (File(versionsDir, name).exists()) name = "$wanted ($n)".also { n++ }
        return name
    }

    private fun baseName(file: String) =
        file.lowercase().substringBeforeLast('.').replace(Regex("""[\d.\-_+\[\]()v ]+"""), "")

    /**
     * Copies the player's things from the old version folder into the freshly installed one.
     * Worlds and settings of the player win; pack files of the new release are not overwritten
     * otherwise. Nothing is removed from the old folder.
     */
    suspend fun migrate(oldDir: File, newDir: File): Migration = withContext(Dispatchers.IO) {
        var worlds = 0
        var settings = 0
        var mods = 0
        var packs = 0
        var other = 0

        // worlds: the player's progress always wins
        File(oldDir, "saves").listFiles()?.filter { it.isDirectory }?.forEach { world ->
            runCatching {
                world.copyRecursively(File(newDir, "saves/${world.name}"), overwrite = true)
                worlds++
            }.onFailure { Logger.warning(TAG, "World ${world.name} not copied", it) }
        }

        // settings and the server list
        listOf("options.txt", "optionsof.txt", "optionsshaders.txt", "servers.dat", "servers.dat_old",
            "usercache.json", "usernamecache.json", "knownkeys.txt").forEach { name ->
            val f = File(oldDir, name)
            if (f.isFile) runCatching { f.copyTo(File(newDir, name), overwrite = true); settings++ }
        }

        // map data, screenshots, schematics: only what the new folder does not have
        listOf("screenshots", "schematics", "journeymap", "XaeroWaypoints", "XaeroWorldMap", "local", "backups")
            .forEach { name ->
                val dir = File(oldDir, name)
                if (dir.isDirectory) other += copyMissing(dir, File(newDir, name))
            }

        // resource packs and shader packs the player added
        listOf("resourcepacks", "shaderpacks").forEach { name ->
            val dir = File(oldDir, name)
            if (dir.isDirectory) packs += copyMissing(dir, File(newDir, name))
        }

        // mods the player added (not from the old pack, not added by ONA, not in the new pack)
        val oldSource = PackSources.read(oldDir)
        val packMods = oldSource?.packMods?.toSet()
        val onaMods = File(oldDir, "ona_speed_mods_added.txt").takeIf { it.isFile }
            ?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        if (packMods != null) {
            val newMods = File(newDir, "mods").listFiles()?.map { it.name } ?: emptyList()
            val newBases = newMods.map { baseName(it) }.toSet()
            File(oldDir, "mods").listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".jar") || it.name.endsWith(".zip")) }
                ?.filter { it.name !in packMods && it.name !in onaMods && !it.name.startsWith("ona-boost") }
                ?.filter { it.name !in newMods && baseName(it.name) !in newBases }
                ?.forEach { jar ->
                    runCatching { jar.copyTo(File(newDir, "mods/${jar.name}"), overwrite = false); mods++ }
                }
        }

        PackSources.read(newDir)?.let { src ->
            src.updatedFrom = oldDir.name
            PackSources.write(newDir, src)
        }

        Logger.info(TAG, "Moved from ${oldDir.name} to ${newDir.name}: $worlds worlds, $settings settings, $mods mods, $packs packs, $other other")
        Migration(worlds, settings, mods, packs, other, modsUnknown = packMods == null)
    }

    private fun copyMissing(from: File, to: File): Int {
        var count = 0
        from.listFiles()?.forEach { f ->
            val target = File(to, f.name)
            if (target.exists()) return@forEach
            runCatching {
                if (f.isDirectory) f.copyRecursively(target, overwrite = false)
                else f.copyTo(target, overwrite = false)
                count++
            }.onFailure { Logger.warning(TAG, "Not copied: $f", it) }
        }
        return count
    }
}
