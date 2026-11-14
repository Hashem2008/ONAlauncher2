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

import com.google.gson.GsonBuilder
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File

/**
 * ONA: where a modpack version came from, so the launcher can find its newest release later.
 * Written into the version folder as [FILE]:
 * - by the pack parsers (name, pack version, Minecraft version, platform) for downloads AND imports,
 * - by the final install step (project / file id when known, the mod files the pack brought).
 * [packMods] tells pack mods from mods the player added later (those move along on an update).
 */
class PackSource(
    var platform: String? = null,
    var projectId: String? = null,
    var versionId: String? = null,
    var name: String = "",
    var version: String = "",
    var minecraft: String = "",
    var packMods: List<String>? = null,
    var updatedFrom: String? = null
)

object PackSources {
    private const val TAG = "PackSource"
    const val FILE = "ona_pack_source.json"
    private val GSON = GsonBuilder().setPrettyPrinting().create()

    fun read(dir: File): PackSource? = runCatching {
        val file = File(dir, FILE)
        if (!file.isFile) return null
        GSON.fromJson(file.readText(), PackSource::class.java)
    }.onFailure { Logger.warning(TAG, "Could not read $FILE in $dir", it) }.getOrNull()

    fun write(dir: File, source: PackSource) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$FILE.part")
            tmp.writeText(GSON.toJson(source))
            val file = File(dir, FILE)
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Logger.warning(TAG, "Could not write $FILE in $dir", it) }
    }

    /** Called by the pack parsers: what the manifest says about the pack itself. */
    fun fromManifest(dir: File, platform: String, name: String, version: String, minecraft: String) {
        write(dir, PackSource(platform = platform, name = name, version = version, minecraft = minecraft))
    }

    /**
     * Called after the pack files were copied into the real version folder: adds the ids (online
     * installs know them) and the list of mod files the pack brought.
     */
    fun finishInstall(versionDir: File, projectId: String? = null, versionId: String? = null) {
        runCatching {
            val source = read(versionDir) ?: return
            if (projectId != null) source.projectId = projectId
            if (versionId != null) source.versionId = versionId
            source.packMods = File(versionDir, "mods").listFiles()
                ?.filter { it.isFile }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()
            write(versionDir, source)
            Logger.info(TAG, "Pack ${source.name} ${source.version} (${source.platform} ${source.projectId}/${source.versionId}), ${source.packMods?.size} mods")
        }.onFailure { Logger.warning(TAG, "Could not finish $FILE in $versionDir", it) }
    }
}
