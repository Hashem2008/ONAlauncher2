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
