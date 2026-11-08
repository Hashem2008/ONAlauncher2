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

package com.movtery.zalithlauncher.utils.device

import android.app.ActivityManager
import android.app.GameManager
import android.content.Context
import android.os.Build
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.platform.bytesToMB
import com.movtery.zalithlauncher.utils.platform.getTotalMemory
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

/**
 * Device dependent defaults carried over from ONAlauncher: big core affinity, Android Game Mode
 * and lighter first-launch settings for phones that are not high end.
 */
object OnaTweaks {
    private const val TAG = "OnaTweaks"

    /** Must match the step of the resolution slider */
    private const val RESOLUTION_STEP = 5

    private fun cpuMaxFreq(core: Int): Long? = runCatching {
        File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
    }.getOrNull()

    private fun hasAllCoreSameFreq(): Boolean {
        val cores = Runtime.getRuntime().availableProcessors()
        val first = cpuMaxFreq(0) ?: return true
        val last = cpuMaxFreq(cores - 1) ?: return true
        return first == last
    }

    /** big.LITTLE CPUs benefit from keeping the game threads off the power saving cores */
    fun hasBigLittleCpu(): Boolean = Runtime.getRuntime().availableProcessors() >= 6 && !hasAllCoreSameFreq()

    /** @return One of GameManager.GAME_MODE_*, or 0 when the system has no game mode */
    fun systemGameMode(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0
        return context.getSystemService(GameManager::class.java)?.gameMode ?: 0
    }

    /** Big cores on big.LITTLE CPUs, overridden by the Game Dashboard / OEM game booster mode */
    fun shouldUseBigCoreAffinity(context: Context): Boolean {
        return when (systemGameMode(context)) {
            GameManager.GAME_MODE_PERFORMANCE -> true
            GameManager.GAME_MODE_BATTERY -> false
            else -> hasBigLittleCpu()
        }
    }

    /** Sustained performance caps the clocks, so only use it when the user did not ask for performance */
    fun shouldUseSustainedPerformance(context: Context, userSetting: Boolean): Boolean {
        return when (systemGameMode(context)) {
            GameManager.GAME_MODE_PERFORMANCE -> false
            GameManager.GAME_MODE_BATTERY -> true
            else -> userSetting
        }
    }

    /** Weak or old phones that need lower defaults to stay playable */
    fun isDeviceLowEnd(context: Context): Boolean {
        if (Architecture.is32BitsDevice) return true
        if (getTotalMemory(context).bytesToMB() <= 3072) return true
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager?.isLowRamDevice == true) return true
        return Runtime.getRuntime().availableProcessors() <= 4
    }

    /** Phones that can take the full defaults */
    fun isDevicePowerful(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (getTotalMemory(context).bytesToMB() <= 4096) return false
        val metrics = context.resources.displayMetrics
        if (min(metrics.widthPixels, metrics.heightPixels) < 1080) return false
        if (Runtime.getRuntime().availableProcessors() <= 4) return false
        return !hasAllCoreSameFreq()
    }

    /** Resolution percentage that renders around 1080p, 720p or 540p depending on the device */
    fun bestResolutionRatio(context: Context): Int {
        val metrics = context.resources.displayMetrics
        val minSide = min(metrics.widthPixels, metrics.heightPixels)
        val targetSide = when {
            isDevicePowerful(context) -> 1080
            isDeviceLowEnd(context) -> 540
            else -> 720
        }
        if (minSide <= targetSide) return 100
        val ratio = 100f * targetSide / minSide
        return (ceil(ratio / RESOLUTION_STEP) * RESOLUTION_STEP).toInt().coerceIn(25, 100)
    }

    /**
     * Heap for this launch: big modpacks and shaders run out of memory with the default, so grow it
     * with the mod count, never past what the phone can spare.
     */
    fun tunedHeap(context: Context, configured: Int, gameDir: File): Int {
        if (Architecture.is32BitsDevice) return configured
        val mods = File(gameDir, "mods").listFiles { file -> file.name.endsWith(".jar") }?.size ?: 0
        val shaders = File(gameDir, "shaderpacks").listFiles()?.any { !it.name.endsWith(".txt") } == true
        val wanted = when {
            mods >= 250 -> 6144
            mods >= 150 -> 5120
            mods >= 80 -> 4096
            mods >= 30 -> 3072
            else -> 0
        } + (if (shaders && mods >= 30) 512 else 0)
        val deviceRam = getTotalMemory(context).bytesToMB().toInt()
        // Phones with 8 GB and more: big modpacks get room to breathe (fewer GC stutters)
        val roomy = if (deviceRam >= 7500 && mods >= 30) 1024 else 0
        // The last launch ran out of memory: give it one more step this time
        val afterOom = if (oomMarker(gameDir).isFile) 1024 else 0
        // Leave more than half of the phone to Android, the GPU driver and the renderer
        val ceiling = (deviceRam * 0.45).toInt()
        val heap = maxOf(configured, min(maxOf(wanted, configured) + roomy + afterOom, ceiling))
        if (heap != configured) Logger.info(TAG, "Raised heap to ${heap}M for $mods mods${if (shaders) " and shaders" else ""}")
        return heap
    }

    /** Written when the game died with OutOfMemoryError, removed after a launch that ends well */
    fun oomMarker(gameDir: File) = File(gameDir, ".ona_out_of_memory")

    /**
     * G1 tuned for short pauses unless the user picked a collector. Fewer and shorter GC
     * pauses are most of the frame drops with many mods.
     */
    fun gcArgs(userArgs: List<String>, heap: Int): List<String> {
        if (userArgs.any { it.startsWith("-XX:+Use") && it.endsWith("GC") }) return emptyList()
        return listOf(
            "-XX:+UseG1GC",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:MaxGCPauseMillis=37",
            "-XX:G1NewSizePercent=23",
            "-XX:G1ReservePercent=20",
            "-XX:G1HeapRegionSize=${if (heap >= 4096) 16 else 8}M",
            "-XX:InitiatingHeapOccupancyPercent=20",
            "-XX:+ParallelRefProcEnabled",
            "-XX:+DisableExplicitGC",
            "-XX:+PerfDisableSharedMem",
            // Aikar's flags: short lived garbage (chunk meshes, packets) dies young instead of
            // filling the old generation, and mixed collections run in small steps
            "-XX:G1MaxNewSizePercent=40",
            "-XX:G1HeapWastePercent=5",
            "-XX:G1MixedGCCountTarget=4",
            "-XX:G1MixedGCLiveThresholdPercent=90",
            "-XX:SurvivorRatio=32",
            "-XX:MaxTenuringThreshold=1",
            // Hundreds of mods keep many copies of the same strings (ids, names)
            "-XX:+UseStringDeduplication",
            // A Java that does not know one of these flags skips it instead of not starting
            "-XX:+IgnoreUnrecognizedVMOptions"
        )
    }

    /**
     * JIT options for more CPU-side FPS, on every Java the launcher ships (8 to 25):
     *  - DontCompileHugeMethods off: methods over 8000 bytes of bytecode are normally never compiled
     *    and stay interpreted; Minecraft has several (world generation, rendering) and mixins make
     *    mod-patched methods even bigger, so they ran many times slower than the rest.
     *  - MaxInlineLevel 15: the default of Java 14+ also on Java 8 (1.12 and older), deeper inlining
     *    of Minecraft's small getter chains.
     * The user's own values win.
     */
    fun jitArgs(userArgs: List<String>): List<String> = buildList {
        if (userArgs.none { it.contains("DontCompileHugeMethods") }) add("-XX:-DontCompileHugeMethods")
        if (userArgs.none { it.startsWith("-XX:MaxInlineLevel") }) add("-XX:MaxInlineLevel=15")
    }

    /**
     * Frame rate options from the launcher settings written into options.txt before each launch.
     * @param cap 0 leaves the game setting alone, 260 is unlimited
     * @param maxRenderDistance 0 leaves it alone, otherwise render and simulation distance are
     *                          lowered to it when they are higher (never raised)
     */
    fun applyFpsOptions(gameDir: File, cap: Int, disableVsync: Boolean, maxRenderDistance: Int = 0) {
        if (cap <= 0 && !disableVsync && maxRenderDistance <= 0) return
        val options = File(gameDir, "options.txt")
        runCatching {
            val lines = if (options.exists()) options.readLines().toMutableList() else mutableListOf()
            fun put(key: String, value: String) {
                val index = lines.indexOfFirst { it.startsWith("$key:") }
                if (index >= 0) lines[index] = "$key:$value" else lines.add("$key:$value")
            }
            if (cap > 0) put("maxFps", cap.toString())
            if (maxRenderDistance > 0) {
                fun lower(key: String, max: Int) {
                    val index = lines.indexOfFirst { it.startsWith("$key:") }
                    val value = if (index >= 0) lines[index].substringAfter(':').trim().toIntOrNull() else null
                    if (value == null || value > max) put(key, max.toString())
                }
                lower("renderDistance", maxRenderDistance)
                lower("simulationDistance", maxOf(5, maxRenderDistance * 2 / 3))
            }
            if (disableVsync) put("enableVsync", "false")
            if (!gameDir.isDirectory && !gameDir.mkdirs()) return
            options.writeText(lines.joinToString("\n", postfix = "\n"))
        }.onFailure {
            Logger.warning(TAG, "Could not write fps options", it)
        }
    }

    /**
     * Big modpacks starve the integrated server on a phone ("Can't keep up! Running 18000ms
     * behind"): block breaking lags and the frame rate drops with it. Simulation distance (how far
     * entities, crops and machines tick, not how far you see) is lowered for them, never raised,
     * and only on versions that have the option (1.18+).
     */
    /**
     * How many cores the game really runs on: the same choice bigcoreaffinity.c makes (LITTLE ARM
     * cores out when at least 4 bigger ones stay, else the clock rule, never fewer than 4). Java
     * sizes its GC and worker pools from this, so it must not count cores the game cannot use.
     */
    fun gameCoreCount(bigCoreAffinity: Boolean): Int {
        val all = Runtime.getRuntime().availableProcessors()
        if (!bigCoreAffinity) return all
        return runCatching {
            val freqs = (0 until all).map { core ->
                runCatching {
                    File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                }.getOrDefault(0L)
            }
            val online = freqs.count { it > 0 }
            val max = freqs.maxOrNull() ?: 0L
            if (online == 0 || max == 0L) return all
            val littleLimit = max / 10 * 7
            val little = BooleanArray(all)
            var current = -1
            var implementer = 0
            var partsKnown = false
            File("/proc/cpuinfo").readLines().forEach { line ->
                val value = line.substringAfter(':', "").trim()
                when {
                    line.startsWith("processor") -> { current = value.toIntOrNull() ?: -1; implementer = 0 }
                    line.startsWith("CPU implementer") -> implementer = value.removePrefix("0x").toIntOrNull(16) ?: 0
                    line.startsWith("CPU part") && current in 0 until all -> {
                        val part = value.removePrefix("0x").toIntOrNull(16) ?: 0
                        little[current] = (implementer == 0x41 && part in setOf(0xc07, 0xd04, 0xd03, 0xd05, 0xd46, 0xd80)) ||
                            (implementer == 0x51 && part in setOf(0x801, 0x803, 0x805)) // Qualcomm Kryo Silver
                        partsKnown = true
                    }
                }
            }
            val anyBig = partsKnown && freqs.indices.any { freqs[it] > 0 && !little[it] }
            fun count(useParts: Boolean) = freqs.indices.count { i ->
                freqs[i] > 0 && !(useParts && little[i]) && freqs[i] >= littleLimit
            }
            var big = count(anyBig)
            if (big < 4 && anyBig) big = count(false)
            if (big < 4 || big >= online) online else big
        }.getOrDefault(all).coerceAtLeast(1)
    }

    fun capSimulationDistance(gameDir: File) {
        val jars = File(gameDir, "mods").listFiles { file -> file.name.endsWith(".jar") }.orEmpty()
        val mods = jars.size
        // ONA: Voxy draws the far terrain itself. A vanilla render distance of 32-55 next to it
        // made the integrated server hold thousands of chunks: leaving a world took minutes
        // ("waiting for 4024 chunks to unload"). Voxy's own LOD distance is not touched.
        val voxy = jars.any { it.name.lowercase().startsWith("voxy") }
        val maxSimulation = when {
            voxy -> 8
            mods >= 150 -> 6
            mods >= 80 -> 8
            else -> return
        }
        val maxRender = if (voxy) 16 else null
        val options = File(gameDir, "options.txt")
        if (!options.isFile) return
        runCatching {
            val lines = options.readLines().toMutableList()
            var changed = false
            fun lower(key: String, max: Int) {
                val index = lines.indexOfFirst { it.startsWith("$key:") }
                if (index < 0) return
                val value = lines[index].substringAfter(':').trim().toIntOrNull() ?: return
                if (value <= max) return
                lines[index] = "$key:$max"
                changed = true
                Logger.info(TAG, "$key $value -> $max ($mods mods${if (voxy) ", Voxy" else ""})")
            }
            lower("simulationDistance", maxSimulation)
            maxRender?.let { lower("renderDistance", it) }
            if (changed) options.writeText(lines.joinToString("\n", postfix = "\n"))
        }.onFailure { Logger.warning(TAG, "Could not lower the simulation distance", it) }
    }

    /**
     * ONA auto settings: view and simulation distance never above what the device carries (only
     * lowered, every launch). Flagships are left alone.
     */
    fun autoDistances(gameDir: File, tier: DeviceTier) {
        val (maxView, maxSimulation) = when (tier) {
            DeviceTier.LOW -> 5 to 5
            DeviceTier.MID -> 6 to 5
            DeviceTier.HIGH -> 10 to 8
            DeviceTier.FLAGSHIP -> return
        }
        val options = File(gameDir, "options.txt")
        if (!options.isFile) return
        runCatching {
            val lines = options.readLines().toMutableList()
            var changed = false
            fun lower(key: String, max: Int) {
                val index = lines.indexOfFirst { it.startsWith("$key:") }
                if (index < 0) return
                val value = lines[index].substringAfter(':').trim().toIntOrNull() ?: return
                if (value <= max) return
                lines[index] = "$key:$max"
                changed = true
                Logger.info(TAG, "Auto settings: $key $value -> $max ($tier)")
            }
            lower("renderDistance", maxView)
            lower("simulationDistance", maxSimulation)
            if (changed) options.writeText(lines.joinToString("\n", postfix = "\n"))
        }.onFailure { Logger.warning(TAG, "Auto settings: could not set the distances", it) }
    }

    /**
     * Minecraft 1.12 and older uploads only some mipmap levels; GL4ES / Krypton then sample the
     * missing ones as white, so terrain further away looked like white water ("Ultra Realisim").
     * Mipmaps off keeps every distance textured.
     */
    fun disableLegacyMipmaps(gameDir: File) {
        val options = File(gameDir, "options.txt")
        if (!options.isFile) return
        runCatching {
            val lines = options.readLines().toMutableList()
            val index = lines.indexOfFirst { it.startsWith("mipmapLevels:") }
            if (index < 0) return
            val value = lines[index].substringAfter(':').trim().toIntOrNull() ?: return
            if (value == 0) return
            lines[index] = "mipmapLevels:0"
            options.writeText(lines.joinToString("\n", postfix = "\n"))
            Logger.info(TAG, "mipmapLevels $value -> 0 (legacy version on GL4ES / Krypton)")
        }.onFailure { Logger.warning(TAG, "Could not turn mipmaps off", it) }
    }

    /**
     * Give a fresh game folder lighter video settings on phones that are not high end, so the
     * first launch is playable. Never touches an existing options.txt.
     */
    fun applyGameDefaults(context: Context, gameDir: File) {
        val options = File(gameDir, "options.txt")
        if (options.exists() || isDevicePowerful(context)) return
        val lowEnd = isDeviceLowEnd(context)
        val lines = listOf(
            "renderDistance:${if (lowEnd) 6 else 8}",
            "simulationDistance:${if (lowEnd) 5 else 6}",
            "graphicsMode:0",
            "fancyGraphics:false",
            "renderClouds:\"false\"",
            "particles:${if (lowEnd) 2 else 1}",
            "entityShadows:false",
            "biomeBlendRadius:${if (lowEnd) 0 else 1}",
            "mipmapLevels:${if (lowEnd) 0 else 2}",
            "ao:${!lowEnd}",
            "entityDistanceScaling:${if (lowEnd) "0.75" else "1.0"}",
            "maxFps:60",
            "enableVsync:false",
        )
        runCatching {
            if (!gameDir.isDirectory && !gameDir.mkdirs()) return
            options.writeText(lines.joinToString("\n", postfix = "\n"))
            Logger.info(TAG, "Wrote ${if (lowEnd) "low-end" else "mid-range"} defaults to $options")
        }.onFailure {
            Logger.warning(TAG, "Could not write default options", it)
        }
    }
}
