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

import com.movtery.zalithlauncher.path.PathManager
import java.io.File
import java.util.zip.ZipFile

/**
 * ONA: Distant Horizons 3.3.x carries sqlite-jdbc renamed to "dh_sqlite" without the Linux-Android
 * natives 3.0.x still had, and stops the game at start ("No native library found for
 * os.name=Linux-Android, os.arch=aarch64"). Its loader reads dh_sqlite.lib.path / dh_sqlite.lib.name
 * first: they point at the launcher's own build (tools/sqlite-jdbc-android, sqlite-jdbc 3.53.2.1 with
 * DH's JNI names). Only when the jar has no phone build of its own; the mod is not touched.
 */
object DhSqliteSupport {
    private const val BUILD = "libona_sqlitejdbc_dh.so"
    private const val LOADER = "dh_sqlite/SQLiteJDBCLoader.class"
    private const val ANDROID_NATIVES = "dh_sqlite/native/Linux-Android/"

    /** True when a jar of the folder has DH's sqlite-jdbc but no Android natives for it */
    fun needed(modsDir: File): Boolean {
        val jars = modsDir.listFiles { file -> file.isFile && file.name.endsWith(".jar") } ?: return false
        return jars.any { jar ->
            runCatching {
                ZipFile(jar).use { zip ->
                    if (zip.getEntry(LOADER) == null) return@use false
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        if (entries.nextElement().name.startsWith(ANDROID_NATIVES)) return@use false
                    }
                    true
                }
            }.getOrDefault(false)
        }
    }

    /** JVM properties for the game, with a line for the launch log; empty when not needed */
    fun jvmArgs(modsDir: File): Pair<List<String>, String?> {
        if (!needed(modsDir)) return emptyList<String>() to null
        val lib = File(PathManager.DIR_NATIVE_LIB, BUILD)
        if (!lib.isFile) return emptyList<String>() to "Distant Horizons sqlite: no phone build in this APK"
        return listOf(
            "-Ddh_sqlite.lib.path=${PathManager.DIR_NATIVE_LIB}",
            "-Ddh_sqlite.lib.name=$BUILD"
        ) to "Distant Horizons sqlite: phone build ($BUILD)"
    }
}
