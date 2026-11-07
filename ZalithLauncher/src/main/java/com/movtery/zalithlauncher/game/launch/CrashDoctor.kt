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

import android.content.Context
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.version.mod.MobileModRules
import com.movtery.zalithlauncher.utils.logging.Logger
import java.io.File
import java.io.RandomAccessFile

/**
 * ONA: reads the game log, the JVM fatal error report (hs_err) and the Minecraft crash report
 * after a crash and says in plain words what went wrong and what fixes it.
 */
object CrashDoctor {
    private const val TAG = "CrashDoctor"

    /** Only the end of a log matters, and modpack logs can be huge */
    private const val TAIL_BYTES = 768 * 1024L

    /** Reports older than this belong to an earlier crash */
    private const val FRESH_MS = 15 * 60 * 1000L

    enum class Cause(val titleRes: Int, val fixRes: Int) {
        /** Native crash inside the renderer, the GPU driver or a memory copy they made */
        RENDERER(R.string.ona_crash_renderer, R.string.ona_crash_renderer_fix),
        /** Native crash somewhere else (JVM, a mod's native library) */
        NATIVE(R.string.ona_crash_native, R.string.ona_crash_native_fix),
        OUT_OF_MEMORY(R.string.ona_crash_oom, R.string.ona_crash_oom_fix),
        HEAP_TOO_BIG(R.string.ona_crash_heap, R.string.ona_crash_heap_fix),
        MISSING_MODS(R.string.ona_crash_missing, R.string.ona_crash_missing_fix),
        DUPLICATE_MODS(R.string.ona_crash_duplicate, R.string.ona_crash_duplicate_fix),
        MOD_CONFLICT(R.string.ona_crash_mixin, R.string.ona_crash_mixin_fix),
        WRONG_JAVA(R.string.ona_crash_java, R.string.ona_crash_java_fix),
        GRAPHICS(R.string.ona_crash_graphics, R.string.ona_crash_graphics_fix),
        SHADER(R.string.ona_crash_shader, R.string.ona_crash_shader_fix),
        /** A mod ships a native library built for PC (x86) only */
        PC_NATIVE(R.string.ona_crash_pc_native, R.string.ona_crash_pc_native_fix),
        /** PhysX (Physics Mod) without an Android build for its version */
        PHYSX_OLD(R.string.ona_crash_physx, R.string.ona_crash_physx_fix),
        /** A Forge 1.12 Kotlin mod (Better Foliage...) without the Kotlin runtime (Forgelin) */
        KOTLIN_MISSING(R.string.ona_crash_kotlin, R.string.ona_crash_kotlin_fix),
        /** Fabric: a required mod is installed in a version the other mod does not accept */
        WRONG_MOD_VERSION(R.string.ona_crash_mod_version, R.string.ona_crash_mod_version_fix)
    }

    class Diagnosis(val cause: Cause, val detail: String?) {
        /** The cause and how to fix it, ready to show */
        fun describe(context: Context): String = buildString {
            append(context.getString(cause.titleRes))
            detail?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(')') }
            append('\n')
            append(context.getString(cause.fixRes))
        }

        /** A different renderer is worth trying on the next launch */
        val rendererSuspect: Boolean
            get() = cause == Cause.RENDERER || cause == Cause.NATIVE || cause == Cause.GRAPHICS
    }

    private val RENDERER_LIBS = listOf(
        "gl4es", "mobileglues", "osmesa", "glxshim", "egl_mesa", "zink", "vulkan", "freedreno",
        "kgsl", "turnip", "libgles", "libegl", "adreno", "mali", "libgsl", "libsc-a", "liblwjgl_opengl",
        "virgl", "panfrost", "krypton"
    )

    /**
     * @param launcherLog the launcher's game log (has the native output and the JVM banner)
     * @param gameDir the folder the game ran in, where hs_err and crash-reports end up
     */
    fun analyze(launcherLog: File?, gameDir: File?): Diagnosis? = runCatching {
        analyzeInner(launcherLog, gameDir)?.let { diagnosis ->
            // Name render mods known to break GL4ES based renderers when the renderer crashed
            if (diagnosis.cause != Cause.RENDERER || gameDir == null) return@let diagnosis
            val risky = MobileModRules.riskyMods(File(gameDir, "mods"))
            if (risky.isEmpty()) diagnosis
            else Diagnosis(diagnosis.cause, listOfNotNull(diagnosis.detail, "suspect mods: " + risky.joinToString()).joinToString(", "))
        }
    }.onFailure { Logger.warning(TAG, "Crash analysis failed", it) }.getOrNull()

    private fun analyzeInner(launcherLog: File?, gameDir: File?): Diagnosis? = runCatching {
        val now = System.currentTimeMillis()
        fun fresh(file: File) = now - file.lastModified() < FRESH_MS
        val hsErr = gameDir?.listFiles { file -> file.name.startsWith("hs_err_pid") && file.name.endsWith(".log") }
            ?.filter(::fresh)?.maxByOrNull { it.lastModified() }
        val crashReport = gameDir?.let { File(it, "crash-reports") }?.listFiles()
            ?.filter(::fresh)?.maxByOrNull { it.lastModified() }
        val gameLatest = gameDir?.let { File(it, "logs/latest.log") }?.takeIf { it.isFile && fresh(it) }

        val text = buildString {
            launcherLog?.takeIf { it.isFile }?.let { append(tail(it)).append('\n') }
            crashReport?.let { append(tail(it)).append('\n') }
            gameLatest?.let { append(tail(it)).append('\n') }
        }
        // The thread, the frames and the native stack are at the top of hs_err
        val hsErrText = hsErr?.let { head(it, 256 * 1024) }

        diagnose(text, hsErrText)
    }.onFailure { Logger.warning(TAG, "Crash analysis failed", it) }.getOrNull()

    fun diagnose(text: String, hsErr: String?): Diagnosis? {
        // A mod tried to load a desktop-only native library
        if (text.contains("UnsatisfiedLinkError") && (text.contains("dlopen failed") || text.contains("wrong ELF class") ||
                    text.contains("EM_X86_64") || text.contains("is for EM_"))
        ) {
            val mod = Regex("-- MOD ([\\w.-]+) --").find(text)?.groupValues?.get(1)
                ?: Regex("Mod File: .*/([^/\\s]+\\.jar)").find(text)?.groupValues?.get(1)
            val physx = text.contains("physx", ignoreCase = true)
            return Diagnosis(if (physx) Cause.PHYSX_OLD else Cause.PC_NATIVE, mod)
        }
        // Java level problems first, they explain a following native abort
        if (Regex("(NoClassDefFoundError: kotlin/|ClassNotFoundException: kotlin\\.)").containsMatchIn(text)) {
            val mod = Regex("at (?:mods|net|com|io|org)\\.(\\w+)\\.").find(
                text.substringAfter("NoClassDefFoundError: kotlin/", "")
            )?.groupValues?.get(1)
            return Diagnosis(Cause.KOTLIN_MISSING, mod)
        }
        // Fabric names the exact swap: "Replace mod 'Sodium' (sodium) 0.9.2+mc26.3 with version 0.9.3-beta.1+mc26.3."
        if (text.contains("Incompatible mods found") || text.contains("but only the wrong version is present")) {
            val swaps = Regex("Replace mod '([^']+)' \\([\\w.-]+\\) (\\S+) with version (\\S+?)\\.?(?:\\s|$)").findAll(text)
                .map { "${it.groupValues[1]} ${it.groupValues[2]} → ${it.groupValues[3]}" }.distinct().toList()
            if (swaps.isNotEmpty() || text.contains("but only the wrong version is present")) {
                return Diagnosis(Cause.WRONG_MOD_VERSION, swaps.take(4).joinToString(", ").ifEmpty { null })
            }
        }
        Regex("requires (?:any version|version [^ ]+(?: or later)?) of '?([\\w.-]+)'?, which is missing").findAll(text)
            .map { it.groupValues[1] }.distinct().toList().takeIf { it.isNotEmpty() }?.let {
                return Diagnosis(Cause.MISSING_MODS, it.take(6).joinToString(", "))
            }
        Regex("Mod ID: '([\\w.-]+)', Requested by").findAll(text).map { it.groupValues[1] }.distinct().toList()
            .takeIf { it.isNotEmpty() }?.let { return Diagnosis(Cause.MISSING_MODS, it.take(6).joinToString(", ")) }
        if (text.contains("MissingModsException") || text.contains("Missing or unsupported mandatory dependencies")) {
            return Diagnosis(Cause.MISSING_MODS, null)
        }
        if (text.contains("DuplicateModsFoundException") || text.contains("Found duplicate mods") ||
            text.contains("Duplicate mods found") || text.contains("found a duplicate")
        ) {
            val mod = Regex("Mod ID: '([\\w.-]+)' from mod files").find(text)?.groupValues?.get(1)
            return Diagnosis(Cause.DUPLICATE_MODS, mod)
        }
        if (text.contains("UnsupportedClassVersionError") || text.contains("compiled by a more recent version of the Java Runtime")) {
            return Diagnosis(Cause.WRONG_JAVA, null)
        }
        if (text.contains("Could not reserve enough space") || text.contains("Could not create the Java Virtual Machine")) {
            return Diagnosis(Cause.HEAP_TOO_BIG, null)
        }
        if (text.contains("java.lang.OutOfMemoryError") || hsErr?.contains("Out of Memory Error") == true ||
            text.contains("There is insufficient memory for the Java Runtime")
        ) {
            return Diagnosis(Cause.OUT_OF_MEMORY, null)
        }
        Regex("Mixin \\[?([\\w.$-]+\\.json)[^\\n]*?(?:from mod|FROM MOD) ([\\w.-]+)").find(text)?.takeIf {
            text.contains("MixinApplyError") || text.contains("Mixin apply") || text.contains("InvalidInjectionException") ||
                    text.contains("InjectionError") || text.contains("MixinTransformerError")
        }?.let { return Diagnosis(Cause.MOD_CONFLICT, it.groupValues[2]) }
        if (text.contains("MixinTransformerError") || text.contains("InvalidInjectionException") || text.contains("MixinApplyError")) {
            val mod = Regex("from mod ([\\w.-]+)").find(text)?.groupValues?.get(1)
            return Diagnosis(Cause.MOD_CONFLICT, mod)
        }
        if (text.contains("No supported graphics backend") || text.contains("GLFW error 65543") ||
            text.contains("Failed to create window") || text.contains("Couldn't set pixel format") ||
            text.contains("OpenGL 3.2 core profile") || text.contains("GLFW error 65542")
        ) {
            return Diagnosis(Cause.GRAPHICS, null)
        }
        if (text.contains("Failed to load shader pack") || text.contains("shader compilation failed", ignoreCase = true) &&
            text.contains("iris", ignoreCase = true)
        ) {
            return Diagnosis(Cause.SHADER, null)
        }

        // Native crash: work out where from the problematic frame and the native stack
        val fatal = text.contains("A fatal error has been detected by the Java Runtime") || hsErr != null ||
                Regex("\\bSIG(SEGV|BUS|ABRT|ILL)\\b").containsMatchIn(text)
        if (!fatal) return null
        val frameSource = (hsErr ?: "") + "\n" + text
        val stackLib = hsErr?.substringAfter("Native frames:", "")
            ?.let { Regex("C\\s+\\[(lib[^+\\]]+)").findAll(it).map { m -> m.groupValues[1] }.firstOrNull { lib -> !lib.startsWith("libc.") } }
        val frame = Regex("Problematic frame:\\s*\\n#\\s*C\\s+\\[([^+\\]]+)").find(frameSource)?.groupValues?.get(1)
            ?: Regex("C\\s+\\[(lib[^+\\]]+)").find(frameSource)?.groupValues?.get(1)
        val nativeStack = hsErr?.substringAfter("Native frames:", "")?.take(6000).orEmpty()
        val inRenderer = RENDERER_LIBS.any { lib ->
            frame?.contains(lib, ignoreCase = true) == true || nativeStack.contains(lib, ignoreCase = true)
        } || hsErr?.let {
            it.contains("org.lwjgl.opengl") || it.contains("Render thread") || it.contains("Client thread")
        } == true || frame?.startsWith("libc") == true && text.contains("memmove", ignoreCase = true)
        val where = listOfNotNull(frame, stackLib?.takeIf { it != frame }).joinToString(" ← ")
        return Diagnosis(if (inRenderer) Cause.RENDERER else Cause.NATIVE, where.ifEmpty { null })
    }

    private fun head(file: File, bytes: Int): String =
        file.inputStream().use { input -> String(input.readNBytesCompat(bytes), Charsets.UTF_8) }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun tail(file: File): String {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = (length - TAIL_BYTES).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}
