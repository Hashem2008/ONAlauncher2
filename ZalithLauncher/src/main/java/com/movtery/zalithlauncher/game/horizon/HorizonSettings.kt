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

package com.movtery.zalithlauncher.game.horizon

import android.content.Context
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ONA: Horizon's touch settings from ONA's Rust page. Horizon reads them from `horizon.txt` in
 * its game folder (`key:value` lines, android.rs `preference`) when a world starts:
 *  - touchControls (on unless "false"), touchGestures, touchGyroscope, largerInterface: true/false
 *  - touchOpacity: 0.0 - 1.0, touchSize: 0.3 - 2.0 (missing = fitted to the screen)
 * Every other line Horizon wrote (control layout, menu ball position...) is kept.
 */
object HorizonSettings {
    private const val TAG = "HorizonSettings"

    fun homeDir(context: Context): File = File(context.filesDir, "horizon-rust/game")
    private fun prefsFile(context: Context) = File(homeDir(context), "horizon.txt")

    fun read(context: Context): Map<String, String> {
        val file = prefsFile(context)
        if (!file.isFile) return emptyMap()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Sets the given keys (null removes one), keeping every other line */
    fun write(context: Context, values: Map<String, String?>) {
        runCatching {
            val file = prefsFile(context)
            file.parentFile?.mkdirs()
            val remaining = values.toMutableMap()
            val lines = (if (file.isFile) file.readLines() else emptyList()).mapNotNull { line ->
                val key = line.substringBefore(':').trim()
                if (key.isEmpty() || !remaining.containsKey(key)) return@mapNotNull line.takeIf { it.isNotBlank() }
                remaining.remove(key)?.let { "$key:$it" }
            }
            val added = remaining.mapNotNull { (key, value) -> value?.let { "$key:$it" } }
            file.writeText((lines + added).joinToString("\n", postfix = "\n"))
        }.onFailure { Logger.warning(TAG, "Could not write Horizon's settings", it) }
    }

    /** ONA's Rust page settings into horizon.txt, before every start of Horizon */
    fun applyFromLauncher(context: Context) {
        val size = AllSettings.rustTouchSize.getValue()
        write(
            context,
            mapOf(
                "touchControls" to AllSettings.rustTouchControls.getValue().toString(),
                "touchGestures" to AllSettings.rustTouchGestures.getValue().toString(),
                "touchGyroscope" to AllSettings.rustTouchGyroscope.getValue().toString(),
                "largerInterface" to AllSettings.rustLargerInterface.getValue().toString(),
                "touchOpacity" to (AllSettings.rustTouchOpacity.getValue().coerceIn(10, 100) / 100f).toString(),
                // 0 = Auto: Horizon fits the layout to the screen
                "touchSize" to if (size <= 0) null else (size.coerceIn(30, 200) / 100f).toString(),
            )
        )
        Logger.info(TAG, "Horizon touch settings written: ${read(context)}")
    }

    /** Puts the menu ball back to the top middle, in case it was dragged off the screen */
    fun resetMenuBall(context: Context) = write(context, mapOf("touchMenuBall" to null))

    /** Back to Horizon's default layout */
    fun resetLayout(context: Context) = write(context, mapOf("controlLayout" to null))

    /**
     * Horizon's logs, crash report and settings in one text file in ONA's shared folder, so the
     * player can send it. Horizon keeps them in the app's private folder.
     */
    fun exportLogs(context: Context): File? = runCatching {
        val root = File(context.filesDir, "horizon-rust")
        val found = root.walkTopDown().maxDepth(4)
            .filter { it.isFile && (it.name.endsWith(".log") || it.name == "last-crash.txt" || it.name == "horizon.txt") }
            .sortedByDescending { it.lastModified() }
            .take(8)
            .toList()
        val outDir = File(context.getExternalFilesDir(null), "horizon-rust/logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
        val out = File(outDir, "horizon-log-$stamp.txt")
        out.bufferedWriter().use { writer ->
            writer.appendLine("ONA Launcher: Horizon (Rust) logs, $stamp")
            writer.appendLine("Engine: ${HorizonEngine.state(context)}")
            if (found.isEmpty()) writer.appendLine("No Horizon log yet: start a world in Rust first.")
            found.forEach { file ->
                writer.appendLine()
                writer.appendLine("===== ${file.relativeTo(root).path} (${Date(file.lastModified())}) =====")
                val text = file.readText()
                // The newest part is what matters for a big log
                writer.append(if (text.length > 400_000) text.takeLast(400_000) else text)
                writer.appendLine()
            }
        }
        out
    }.onFailure { Logger.warning(TAG, "Could not export Horizon's logs", it) }.getOrNull()
}
