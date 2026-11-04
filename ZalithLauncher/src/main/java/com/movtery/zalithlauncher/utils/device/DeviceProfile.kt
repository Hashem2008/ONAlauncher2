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

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import com.movtery.zalithlauncher.context.GlobalContext
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.platform.bytesToMB
import com.movtery.zalithlauncher.utils.platform.getTotalMemory
import java.io.File
import kotlin.math.roundToInt

enum class GpuVendor { ADRENO, MALI, XCLIPSE, POWERVR, OTHER }

/** How much the phone can take, from weakest to strongest */
enum class DeviceTier { LOW, MID, HIGH, FLAGSHIP }

/**
 * ONA: what the phone is made of, read once: SoC, GPU, RAM, CPU clocks and Vulkan, plus a
 * 0..100 score the launcher uses to pick renderers, drivers, resolution and to rate content.
 */
data class DeviceProfile(
    val socName: String,
    val gpuVendor: GpuVendor,
    val gpuName: String,
    /** Adreno / Mali / Xclipse model number, 840 for "Adreno (TM) 840", 0 when unknown */
    val gpuModel: Int,
    val ramMb: Long,
    val cores: Int,
    val bigCoreMaxMhz: Int,
    val vulkan: Boolean,
    val gpuScore: Int,
    val score: Int,
    val tier: DeviceTier
) {
    val isAdreno: Boolean get() = gpuVendor == GpuVendor.ADRENO

    /**
     * The Turnip build bundled with the launcher is known good on Adreno 6xx and 7xx. Newer
     * Adreno generations are safer on the vendor Vulkan driver.
     */
    val turnipRecommended: Boolean get() = isAdreno && gpuModel in 600..799
}

object DeviceProfiler {
    private const val TAG = "DeviceProfiler"

    @Volatile
    private var cached: DeviceProfile? = null

    fun get(context: Context): DeviceProfile {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: build(context.applicationContext).also {
                cached = it
                Logger.info(TAG, "Device: $it")
            }
        }
    }

    /** Null until [get] ran once, for callers that must not block */
    fun peek(): DeviceProfile? = cached

    /**
     * [peek], or read it now from the app context. After the first start the GPU strings come
     * from a cache file, so this never creates a GL context in the game process.
     */
    fun current(): DeviceProfile? = cached ?: runCatching { get(GlobalContext) }.getOrNull()

    private fun build(context: Context): DeviceProfile {
        val (vendorString, rendererString) = cachedGlStrings(context)
        val gpuName = rendererString ?: "Unknown GPU"
        val vendor = when {
            gpuName.contains("adreno", true) || vendorString?.contains("qualcomm", true) == true -> GpuVendor.ADRENO
            gpuName.contains("xclipse", true) -> GpuVendor.XCLIPSE
            // ONA: Huawei Maleoon (Kirin 9000S / 9010 / 9020 / 8000) grew out of Mali and its
            // drivers behave like Mali's, so it takes the Mali paths
            gpuName.contains("mali", true) || gpuName.contains("immortalis", true) ||
                gpuName.contains("maleoon", true) -> GpuVendor.MALI
            gpuName.contains("powervr", true) || gpuName.contains("img", true) -> GpuVendor.POWERVR
            else -> GpuVendor.OTHER
        }
        val modelRegex = if (vendor == GpuVendor.MALI) Regex("G(\\d{2,3})") else Regex("(\\d{3})")
        val model = when {
            // Maleoon 910 / 920 keep their own number
            gpuName.contains("maleoon", true) -> Regex("(\\d{3})").find(gpuName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            // Mali-G1-Ultra / Premium / Pro (Dimensity 9500 and later) have no number to read
            vendor == GpuVendor.MALI && Regex("G1-", RegexOption.IGNORE_CASE).containsMatchIn(gpuName) -> 1000
            else -> modelRegex.find(gpuName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

        val ramMb = runCatching { getTotalMemory(context).bytesToMB().toLong() }.getOrDefault(4096L)
        val cores = Runtime.getRuntime().availableProcessors()
        val maxMhz = (0 until cores).maxOfOrNull { core ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000
            }.getOrDefault(0L)
        }?.toInt() ?: 0
        val vulkan = runCatching { checkVulkanSupportSafe(context) }.getOrDefault(false)

        val gpuScore = gpuScore(vendor, model, gpuName)
        val cpuScore = when {
            maxMhz >= 4000 -> 100
            maxMhz >= 3300 -> 85
            maxMhz >= 3000 -> 70
            maxMhz >= 2600 -> 55
            maxMhz >= 2200 -> 40
            maxMhz > 0 -> 25
            else -> 45
        }
        val ramScore = when {
            ramMb >= 15000 -> 100
            ramMb >= 11000 -> 90
            ramMb >= 7500 -> 70
            ramMb >= 5500 -> 50
            ramMb >= 3500 -> 30
            else -> 10
        }
        var score = (gpuScore * 0.55f + cpuScore * 0.25f + ramScore * 0.20f).roundToInt()
        if (Architecture.is32BitsDevice) score = minOf(score, 25)
        val tier = when {
            score >= 80 -> DeviceTier.FLAGSHIP
            score >= 60 -> DeviceTier.HIGH
            score >= 38 -> DeviceTier.MID
            else -> DeviceTier.LOW
        }
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" ")
        } else ""
        return DeviceProfile(
            socName = SocNames.describe(soc.ifBlank { Build.HARDWARE }),
            gpuVendor = vendor,
            gpuName = gpuName,
            gpuModel = model,
            ramMb = ramMb,
            cores = cores,
            bigCoreMaxMhz = maxMhz,
            vulkan = vulkan,
            gpuScore = gpuScore,
            score = score,
            tier = tier
        )
    }

    private fun gpuScore(vendor: GpuVendor, model: Int, name: String): Int = when (vendor) {
        GpuVendor.ADRENO -> when {
            model >= 830 -> 100
            model >= 800 -> 92
            model >= 750 -> 90
            model >= 740 -> 84
            model >= 730 -> 76
            model >= 700 -> 58
            model >= 660 -> 62
            model >= 640 -> 50
            model >= 615 -> 34
            model >= 600 -> 25
            model >= 500 -> 15
            else -> 30
        }
        GpuVendor.MALI -> when {
            // Huawei Maleoon: 910 (Kirin 9000S) about a Mali-G710, 920 (9010/9020) above it
            name.contains("maleoon", true) -> when {
                model >= 930 -> 82
                model >= 920 -> 74
                model >= 910 -> 66
                else -> 52
            }
            // Mali-G1 generation (Dimensity 9500 / 8500 class)
            model >= 1000 -> when {
                name.contains("ultra", true) -> 96
                name.contains("premium", true) -> 80
                else -> 66
            }
            name.contains("immortalis", true) -> 88
            model >= 900 -> 85
            model >= 720 -> 78
            model >= 710 -> 66
            model >= 610 -> 52
            model >= 76 && model < 100 -> 48 // G76 / G77 / G78
            model >= 68 && model < 76 -> 36
            model >= 57 && model < 68 -> 30
            model >= 51 && model < 57 -> 18
            else -> 15
        }
        // Samsung Exynos (AMD RDNA based)
        GpuVendor.XCLIPSE -> when {
            model >= 960 -> 95 // Exynos 2600
            model >= 950 -> 88 // Exynos 2500
            model >= 940 -> 80 // Exynos 2400
            model >= 930 -> 70 // Exynos 2300
            model >= 900 -> 64 // Exynos 2200
            model >= 500 -> 46 // Exynos 1480 / 1580 (Xclipse 530 / 540)
            else -> 58
        }
        // Imagination: MediaTek Helio (GE8320 in Helio G35 / G37 / P35), Dimensity (BXM),
        // Google Tensor G5 (DXT)
        GpuVendor.POWERVR -> when {
            name.contains("dxt", true) -> 72
            name.contains("cxt", true) -> 55
            name.contains("bxm", true) -> 36
            name.contains("gm9", true) -> 26
            name.contains("ge8", true) -> 14
            else -> 22
        }
        GpuVendor.OTHER -> 40
    }

    /**
     * The GPU strings are read once with EGL and kept in a file, so the game process (and
     * every later start) never has to create a GL context just for this.
     */
    private fun cachedGlStrings(context: Context): Pair<String?, String?> {
        val file = File(context.filesDir, "ona_gpu_strings.txt")
        runCatching {
            val lines = file.readLines()
            if (lines.size >= 2 && lines[1].isNotBlank()) return lines[0] to lines[1]
        }
        val strings = runCatching { queryGlStrings() }.getOrDefault(null to null)
        if (strings.second != null) {
            runCatching { file.writeText("${strings.first.orEmpty()}\n${strings.second}\n") }
        }
        return strings
    }

    /** GL_VENDOR / GL_RENDERER from a throwaway pbuffer context, never terminates the shared display */
    private fun queryGlStrings(): Pair<String?, String?> {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, null, 0, null, 0)) return null to null
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return null to null
        val config = configs[0] ?: return null to null
        val context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (context == EGL14.EGL_NO_CONTEXT) return null to null
        val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        val previousContext = EGL14.eglGetCurrentContext()
        val previousDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val previousRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        return try {
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null to null
            GLES20.glGetString(GLES20.GL_VENDOR) to GLES20.glGetString(GLES20.GL_RENDERER)
        } finally {
            if (previousContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglMakeCurrent(display, previousDraw, previousRead, previousContext)
            } else {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }
    }

    private fun checkVulkanSupportSafe(context: Context): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000) // Vulkan 1.1
    }
}

/**
 * ONA: the marketing name of the chip next to the code the system reports, so the device card
 * and the launch log say "Snapdragon 8s Gen 4 (SM8735)" instead of only "QTI SM8735".
 * Only codes that are certain are listed; anything else is shown as reported.
 */
object SocNames {
    private val NAMES = mapOf(
        // Qualcomm Snapdragon
        "SM8850" to "Snapdragon 8 Elite Gen 5",
        "SM8750" to "Snapdragon 8 Elite",
        "SM8735" to "Snapdragon 8s Gen 4",
        "SM8650" to "Snapdragon 8 Gen 3",
        "SM8635" to "Snapdragon 8s Gen 3",
        "SM8550" to "Snapdragon 8 Gen 2",
        "SM8475" to "Snapdragon 8+ Gen 1",
        "SM8450" to "Snapdragon 8 Gen 1",
        "SM8350" to "Snapdragon 888",
        "SM8250" to "Snapdragon 865",
        "SM8150" to "Snapdragon 855",
        "SM7675" to "Snapdragon 7+ Gen 3",
        "SM7550" to "Snapdragon 7 Gen 3",
        "SM7475" to "Snapdragon 7+ Gen 2",
        "SM7435" to "Snapdragon 7s Gen 2",
        "SM7325" to "Snapdragon 778G",
        "SM6375" to "Snapdragon 695",
        "SM6225" to "Snapdragon 680",
        "SM4450" to "Snapdragon 4 Gen 2",
        // MediaTek Dimensity / Helio
        "MT6991" to "Dimensity 9400",
        "MT6989" to "Dimensity 9300",
        "MT6985" to "Dimensity 9200",
        "MT6983" to "Dimensity 9000",
        "MT6897" to "Dimensity 8300",
        "MT6896" to "Dimensity 8200",
        "MT6895" to "Dimensity 8100",
        "MT6886" to "Dimensity 7200",
        "MT6878" to "Dimensity 7300",
        "MT6789" to "Helio G99",
        "MT6785" to "Helio G90 / G95",
        "MT6781" to "Helio G96",
        "MT6769" to "Helio G85 / G88",
        "MT6768" to "Helio G85 / P65",
        "MT6765" to "Helio P35 / G35 / G37",
        "MT6762" to "Helio P22 / G25",
        // Samsung Exynos
        "S5E9955" to "Exynos 2500",
        "S5E9945" to "Exynos 2400",
        "S5E9935" to "Exynos 2300",
        "S5E9925" to "Exynos 2200",
        "S5E9840" to "Exynos 2100",
        "S5E8855" to "Exynos 1580",
        "S5E8845" to "Exynos 1480",
        "S5E8835" to "Exynos 1380",
        "S5E8825" to "Exynos 1280",
        "S5E3830" to "Exynos 850",
        // Huawei HiSilicon Kirin
        "HI36A0" to "Kirin 9000",
        "HI3690" to "Kirin 990",
        "HI3680" to "Kirin 980",
        "HI6260" to "Kirin 710"
    )

    fun describe(reported: String): String {
        if (reported.isBlank()) return reported
        val upper = reported.uppercase()
        // Huawei reports the name itself, e.g. "Kirin9000S"
        Regex("KIRIN\\s*(\\d{3,4}[A-Z]?)").find(upper)?.let { return "Kirin ${it.groupValues[1]}" }
        val code = Regex("(SM\\d{4}|MT\\d{4}|S5E\\d{4}|HI\\d{2}[0-9A-F]\\d)").find(upper)?.value
            ?: return reported
        val name = NAMES[code] ?: return reported
        return "$name ($code)"
    }
}
