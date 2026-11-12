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
import java.util.zip.ZipInputStream

/**
 * ONA: zstd-jni for mods that bring it with desktop natives only.
 * Distant Horizons carries zstd-jni renamed to "dhcomgithubluben.zstd"; its
 * linux/aarch64/libzstd-jni_dh-1.5.7-6.so needs glibc (libpthread.so.0) and DH stops the game at
 * start ("critical setup error: One or more libraries are either in-accessible ...").
 * zstd-jni then calls System.loadLibrary("zstd-jni_dh-1.5.7-6"): the launcher's own build
 * (tools/zstd-jni-android, same 148 JNI functions) is copied under that exact name into a folder
 * of java.library.path before the game starts. Plain zstd-jni (com.github.luben.zstd) the same way.
 */
object ZstdJniSupport {
    /** Our builds in the APK: DH's renamed copy and plain zstd-jni */
    private const val DH_BUILD = "libona_zstdjni_dh.so"
    private const val PLAIN_BUILD = "libona_zstdjni.so"

    /** "linux/aarch64/libzstd-jni_dh-1.5.7-6.so" -> "libzstd-jni_dh-1.5.7-6.so" */
    private val NATIVE_ENTRY = Regex("""(?:^|/)(libzstd-jni(?:_dh)?-[0-9][A-Za-z0-9.\-]*)\.(?:so|dll|dylib)$""")

    /** Library file names (".so") the mods of this folder ask zstd-jni to load */
    fun wantedNames(modsDir: File): Set<String> {
        val names = sortedSetOf<String>()
        val jars = modsDir.listFiles { file -> file.isFile && file.name.endsWith(".jar") } ?: return names
        for (jar in jars) {
            runCatching {
                ZipFile(jar).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name
                        NATIVE_ENTRY.find(name)?.let { names.add(it.groupValues[1] + ".so") }
                        // zstd-jni as a nested jar (Jar-in-Jar)
                        if (name.endsWith(".jar") && name.substringAfterLast('/').startsWith("zstd-jni")) {
                            ZipInputStream(zip.getInputStream(entry)).use { nested ->
                                var inner = nested.nextEntry
                                while (inner != null) {
                                    NATIVE_ENTRY.find(inner.name)?.let { names.add(it.groupValues[1] + ".so") }
                                    inner = nested.nextEntry
                                }
                            }
                        }
                    }
                }
            }
        }
        return names
    }

    /**
     * Copy the phone build under every name the mods ask for into [libDir] (a folder of
     * java.library.path). Never touches the mods.
     * @return one line per library for the launch log, empty when no mod brings zstd-jni
     */
    fun place(modsDir: File, libDir: File): List<String> {
        val wanted = wantedNames(modsDir)
        if (wanted.isEmpty()) return emptyList()
        val report = mutableListOf<String>()
        for (name in wanted) {
            val build = if (name.startsWith("libzstd-jni_dh-")) DH_BUILD else PLAIN_BUILD
            val source = File(PathManager.DIR_NATIVE_LIB, build)
            if (!source.isFile) {
                report.add("$name: no phone build of zstd-jni in this APK")
                continue
            }
            val result = runCatching {
                libDir.mkdirs()
                val target = File(libDir, name)
                if (!target.isFile || target.length() != source.length()) {
                    val part = File(libDir, "$name.part")
                    source.copyTo(part, overwrite = true)
                    if (!part.renameTo(target)) {
                        target.delete()
                        if (!part.renameTo(target)) error("cannot write $target")
                    }
                }
                "$name: phone build ready ($build)"
            }.getOrElse { "$name: could not be placed: ${it.message}" }
            report.add(result)
        }
        return report
    }
}
