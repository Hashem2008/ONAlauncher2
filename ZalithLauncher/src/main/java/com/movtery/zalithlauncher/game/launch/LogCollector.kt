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

package com.movtery.zalithlauncher.game.launch

import android.os.Build
import com.movtery.zalithlauncher.BuildConfig
import com.movtery.zalithlauncher.game.renderer.Renderers
import com.movtery.zalithlauncher.game.renderer.renderers.SmartRenderer
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.utils.device.DeviceProfiler
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ONA: every log a problem needs, in one zip, with one tap: the launcher's log of the last launch
 * (latest_game.log: renderer, Java, device, native messages), the game's own logs, the newest crash
 * reports and JVM crash files, every options file (key bindings, render distance...), the version
 * file, the whole config folder (text files), the mods list, the control layout in use and the
 * launcher's own log, plus a summary on top.
 * Long logs keep their end (where the problem is); account tokens are masked.
 */
object LogCollector {
    private const val TAG = "LogCollector"
    /** A log bigger than this keeps its last part (the start is the same in every launch) */
    private const val MAX_LOG_BYTES = 12L * 1024 * 1024
    private const val MAX_SMALL_FILE_BYTES = 2L * 1024 * 1024
    /** The whole config folder goes in, file by file up to this, all together up to [MAX_CONFIG_BYTES] */
    private const val MAX_CONFIG_FILE_BYTES = 1L * 1024 * 1024
    private const val MAX_CONFIG_BYTES = 24L * 1024 * 1024
    /** Not text: left out of the config folder */
    private val BINARY = setOf("jar", "zip", "png", "jpg", "jpeg", "gif", "webp", "ogg", "wav", "mp3", "dat", "mca", "nbt", "db", "bin", "so", "class", "ttf", "otf")
    /** The files of the last ... count as "this problem" */
    private const val RECENT_MS = 6L * 60 * 60 * 1000
    private val TOKEN = Regex("""(accessToken[^\w\r\n]{1,4})([A-Za-z0-9._\-]{12,})""")
    private val TIME_STAMP = Regex("""^\[[0-9:. -]+]\s*""")
    /** How often the same error is copied before the rest is only counted */
    private const val KEEP_REPEATS = 3
    private const val MAX_TRACKED_EVENTS = 200_000

    /** The version a log file of the launcher belongs to (versions/<name>/<launcher folder>/latest_game.log) */
    fun versionOf(logFile: File): Version? = runCatching {
        VersionsManager.versions.value.firstOrNull { it.getLatestLog().absolutePath == logFile.absolutePath }
    }.getOrNull()

    /** @return the zip, ready to share */
    suspend fun collect(version: Version?): File = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date())
        val name = version?.getVersionName()?.replace(Regex("[^A-Za-z0-9._-]+"), "_")?.take(40) ?: "launcher"
        val dir = File(PathManager.DIR_CACHE, "ona-logs").apply {
            // only the newest zips stay (and no leftovers of a collection that was cut off)
            listFiles { file -> file.name.startsWith("compact-") }?.forEach { it.delete() }
            listFiles()?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
            mkdirs()
        }
        val target = File(dir, "ONA-logs_${name}_$stamp.zip")
        val notes = mutableListOf<String>()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            val added = mutableListOf<String>()
            fun addLog(file: File?, entry: String) {
                if (file == null || !file.isFile || file.length() == 0L) return
                runCatching {
                    // an error repeated every frame (a mod's broken HUD) filled 51 MB and pushed the
                    // start of the log out: repeats are counted instead of copied
                    val compacted = File(dir, "compact-${System.nanoTime()}.log")
                    try {
                        val left = compact(file, compacted)
                        addText(zip, compacted, entry, MAX_LOG_BYTES)
                        added.add("$entry (${file.length() / 1024} KB${if (left > 0) ", $left repeated errors counted, not copied" else ""}${if (compacted.length() > MAX_LOG_BYTES) ", start + end kept" else ""})")
                    } finally {
                        compacted.delete()
                    }
                }.onFailure { notes.add("could not add $entry: ${it.message}") }
            }
            fun addSmall(file: File?, entry: String) {
                if (file == null || !file.isFile || file.length() > MAX_SMALL_FILE_BYTES) return
                runCatching {
                    addText(zip, file, entry, MAX_SMALL_FILE_BYTES)
                    added.add(entry)
                }.onFailure { notes.add("could not add $entry: ${it.message}") }
            }
            val now = System.currentTimeMillis()
            fun recent(file: File) = now - file.lastModified() < RECENT_MS

            if (version != null) {
                val gameDir = version.getGameDir()
                addLog(version.getLatestLog(), "1-launcher-log_latest_game.log")
                addLog(File(version.getZalithVersionPath(), LogName.JVM.fileName), "1-launcher-log_latest_jvm.log")
                addLog(File(gameDir, "logs/latest.log"), "2-game-log_latest.log")
                File(gameDir, "crash-reports").listFiles()?.filter { it.isFile && recent(it) }
                    ?.sortedByDescending { it.lastModified() }?.take(3)
                    ?.forEach { addLog(it, "3-crash-reports/${it.name}") }
                gameDir.listFiles { file -> file.name.startsWith("hs_err_pid") && file.name.endsWith(".log") }
                    ?.filter(::recent)?.sortedByDescending { it.lastModified() }?.take(2)
                    ?.forEach { addLog(it, "3-crash-reports/${it.name}") }
                // every options file of the game and of OptiFine / shaders (key bindings, video settings)
                gameDir.listFiles { file -> file.isFile && file.name.startsWith("options") && file.name.endsWith(".txt") }
                    ?.forEach { addSmall(it, "4-settings/${it.name}") }
                // the version file (loader, libraries, arguments)
                addSmall(File(version.getVersionPath(), "${version.getVersionName()}.json"), "4-settings/${version.getVersionName()}.json")
                // other game logs (KubeJS, debug.log: the end)
                File(gameDir, "logs").walkTopDown().maxDepth(2)
                    .filter { it.isFile && it.name != "latest.log" && it.name.endsWith(".log") && recent(it) }
                    .sortedByDescending { it.lastModified() }.take(6)
                    .forEach { addLog(it, "2-game-log_other/${it.relativeTo(File(gameDir, "logs")).path}") }
                // the whole config folder: mods' settings, default key bindings of the pack...
                var configBytes = 0L
                var configSkipped = 0
                val configDir = File(gameDir, "config")
                configDir.walkTopDown().maxDepth(6).filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                    val tooBig = file.length() > MAX_CONFIG_FILE_BYTES || configBytes + file.length() > MAX_CONFIG_BYTES
                    if (file.extension.lowercase() in BINARY || tooBig) {
                        configSkipped++
                        return@forEach
                    }
                    runCatching {
                        addText(zip, file, "7-config/${file.relativeTo(configDir).path}", MAX_CONFIG_FILE_BYTES)
                        configBytes += file.length()
                    }.onFailure { configSkipped++ }
                }
                added.add("7-config/ (${configBytes / 1024} KB${if (configSkipped > 0) ", $configSkipped big or binary files left out" else ""})")
                File(gameDir, "shaderpacks").listFiles { file -> file.isFile && file.name.endsWith(".txt") }
                    ?.forEach { addSmall(it, "4-settings/shaderpacks/${it.name}") }
                version.getVersionPath().listFiles { file -> file.isFile && file.name.startsWith("ona_") && file.name.endsWith(".txt") }
                    ?.forEach { addSmall(it, "4-settings/launcher/${it.name}") }
                zip.putNextEntry(ZipEntry("5-mods.txt"))
                zip.write(modsList(File(gameDir, "mods")).toByteArray())
                zip.closeEntry()
                added.add("5-mods.txt")
            }
            // the control layout in use (buttons, keys they send)
            runCatching {
                val layout = File(PathManager.DIR_CONTROL_LAYOUTS, AllSettings.controlLayout.getValue())
                if (AllSettings.controlLayout.getValue().isNotEmpty()) addSmall(layout, "8-controls/${layout.name}")
            }
            // the launcher's own log (this run and the one before)
            // ... of every process: the game runs in its own (":game"), with the on-screen input trace
            PathManager.DIR_LAUNCHER_LOGS.listFiles { file -> file.isFile && file.name.endsWith(".log") && file.length() > 0 }
                ?.groupBy { it.name.substringAfterLast('_').substringBefore('.') }
                ?.values?.flatMap { files -> files.sortedByDescending { it.lastModified() }.take(2) }
                ?.forEach { addLog(it, "6-launcher-app/${it.name}") }

            zip.putNextEntry(ZipEntry("0-SUMMARY.txt"))
            zip.write(summary(version, added, notes).toByteArray())
            zip.closeEntry()
        }
        Logger.info(TAG, "Logs collected into ${target.name} (${target.length() / 1024} KB)")
        target
    }

    private fun summary(version: Version?, added: List<String>, notes: List<String>): String = buildString {
        appendLine("ONA Launcher logs - ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())}")
        appendLine("Launcher: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
        runCatching { DeviceProfiler.current() }.getOrNull()?.let { appendLine("ONA Device: $it") }
        if (version != null) {
            appendLine("Version: ${version.getVersionName()} (${runCatching { version.getVersionInfo()?.getInfoString() }.getOrNull() ?: "?"})")
            val renderer = if (version.usesSmartRenderer()) "ONA Smart" else runCatching { version.getRenderer() }.getOrDefault("?")
            val lastUsed = runCatching { if (Renderers.isCurrentRendererValid()) Renderers.getCurrentRenderer().getRendererName() else null }.getOrNull()
            appendLine("Renderer: $renderer${lastUsed?.let { ", last used $it" } ?: ""}")
            SmartRenderer.lastReason.takeIf { it.isNotBlank() }?.let { appendLine("ONA Renderer: $it") }
        }
        runCatching {
            appendLine("Resolution: ${AllSettings.resolutionRatio.getValue()}%, upscaler ${AllSettings.upscalerMode.getValue()}")
            appendLine("Controls: layout ${AllSettings.controlLayout.getValue().ifEmpty { "-" }}, button look ${AllSettings.controlButtonSkin.getValue()}, press ${AllSettings.controlPressAnimation.getValue()}")
            appendLine("ONA Graphics: ${AllSettings.onaGraphics.getValue()}, Shader Community: ${AllSettings.communityShader.getValue()}, ONA Boost: ${AllSettings.onaBoost.getValue()} (${AllSettings.onaBoostTarget.getValue()} FPS)")
        }
        appendLine()
        appendLine("Files:")
        added.forEach { appendLine("  $it") }
        if (notes.isNotEmpty()) {
            appendLine()
            notes.forEach { appendLine("  ! $it") }
        }
    }

    private fun modsList(mods: File): String = buildString {
        val jars = mods.listFiles { file -> file.isFile }?.sortedBy { it.name.lowercase() }.orEmpty()
        appendLine("${jars.count { it.name.endsWith(".jar") }} jars in ${mods.name}")
        jars.forEach { appendLine("${it.name}  ${it.length() / 1024} KB") }
    }

    /**
     * The log with every error that repeats (same text, other time) copied only [KEEP_REPEATS]
     * times; how often each one came is listed at the end. Lines without a time stamp (stack
     * traces) belong to the line before them.
     * @return how many repeats were left out
     */
    private fun compact(source: File, target: File): Int {
        val counts = HashMap<Long, Int>()
        val firstLines = LinkedHashMap<Long, String>()
        var left = 0
        java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(source), Charsets.UTF_8), 64 * 1024).use { reader ->
            target.bufferedWriter(Charsets.UTF_8).use { writer ->
                val event = ArrayList<String>()
                fun flush() {
                    if (event.isEmpty()) return
                    val first = event[0]
                    // only errors and stack traces are compared (plain lines are always kept)
                    val tracked = event.size > 1 || first.contains("/ERROR]") || first.contains("/WARN]")
                    var keep = true
                    if (tracked && counts.size < MAX_TRACKED_EVENTS) {
                        var hash = -0x340d631b7bdddcdbL
                        fun mix(text: String) {
                            for (ch in text) {
                                hash = hash xor ch.code.toLong()
                                hash *= 0x100000001b3L
                            }
                        }
                        mix(first.replace(TIME_STAMP, ""))
                        for (i in 1 until event.size) mix(event[i])
                        val count = (counts[hash] ?: 0) + 1
                        counts[hash] = count
                        if (count > KEEP_REPEATS) {
                            keep = false
                            left++
                            firstLines.putIfAbsent(hash, first.replace(TIME_STAMP, "").take(200))
                        }
                    }
                    if (keep) event.forEach { writer.write(it); writer.write("\n") }
                    event.clear()
                }
                while (true) {
                    val line = reader.readLine() ?: break
                    if (event.isNotEmpty() && line.isNotEmpty() && line[0] == '[' && line.length > 1 && line[1].isDigit()) flush()
                    // one event is never more than a few hundred lines (a cap against odd logs)
                    if (event.size > 400) flush()
                    event.add(line)
                }
                flush()
                if (firstLines.isNotEmpty()) {
                    writer.write("\n[ONA: errors that repeated, each copied $KEEP_REPEATS times above]\n")
                    firstLines.entries.sortedByDescending { counts[it.key] ?: 0 }.take(50).forEach { (hash, line) ->
                        writer.write("  ${counts[hash]}x  $line\n")
                    }
                }
            }
        }
        return left
    }

    /** A text file into the zip: its start and end when it is long, account tokens masked */
    private fun addText(zip: ZipOutputStream, file: File, entry: String, maxBytes: Long) {
        zip.putNextEntry(ZipEntry(entry))
        val writer = zip.bufferedWriter(Charsets.UTF_8)
        fun copy(reader: java.io.BufferedReader, limit: Long) {
            var written = 0L
            while (written < limit) {
                val line = reader.readLine() ?: break
                writer.write(if (line.contains("accessToken")) line.replace(TOKEN, "$1********") else line)
                writer.write("\n")
                written += line.length + 1
            }
        }
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length <= maxBytes) {
                copy(java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(raf.fd), Charsets.UTF_8), 64 * 1024), Long.MAX_VALUE)
            } else {
                // the start (device, renderer, Java, mods) and the end (where it went wrong)
                val head = maxBytes / 6
                copy(java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(raf.fd), Charsets.UTF_8), 64 * 1024), head)
                val tailStart = length - (maxBytes - head)
                writer.write("\n[ONA: ${(tailStart - head) / 1024 / 1024} MB in the middle left out]\n\n")
                raf.seek(tailStart)
                val reader = java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(raf.fd), Charsets.UTF_8), 64 * 1024)
                // the first line after a seek is cut in the middle
                reader.readLine()
                copy(reader, Long.MAX_VALUE)
            }
        }
        writer.flush()
        zip.closeEntry()
    }
}
