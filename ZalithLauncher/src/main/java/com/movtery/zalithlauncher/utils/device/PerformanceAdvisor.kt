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

import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformClasses
import com.movtery.zalithlauncher.game.download.assets.platform.PlatformFilterCode
import com.movtery.zalithlauncher.game.download.assets.platform.curseforge.models.CurseForgeModCategory
import com.movtery.zalithlauncher.game.download.assets.platform.curseforge.models.CurseForgeModpackCategory
import com.movtery.zalithlauncher.game.download.assets.platform.modrinth.models.ModrinthModCategory
import com.movtery.zalithlauncher.game.download.assets.platform.modrinth.models.ModrinthModpackCategory
import com.movtery.zalithlauncher.game.download.assets.platform.modrinth.models.ModrinthShadersCategory
import com.movtery.zalithlauncher.setting.enums.UpscalerMode
import java.io.File

/** How well something should run on this phone */
enum class PerformanceRating(val textRes: Int) {
    /** Makes the game faster */
    BOOST(R.string.ona_perf_boost),
    SMOOTH(R.string.ona_perf_smooth),
    MAY_LAG(R.string.ona_perf_may_lag),
    HEAVY(R.string.ona_perf_heavy)
}

/** Settings that suit this phone */
data class RecommendedSettings(
    val resolutionPercent: Int,
    val upscaler: UpscalerMode,
    val renderDistance: Int,
    val simulationDistance: Int,
    val fpsCap: Int,
    /** Mods that still run comfortably */
    val comfortableMods: Int,
    /** Strongest Modrinth shader performance tag that still runs: 0 none, 1 potato .. 4 high */
    val shaderLevel: Int
)

/**
 * ONA: turns the [DeviceProfile] into advice: which modpacks, mods and shaders run comfortably,
 * which ones will lag, and the settings that fit the phone.
 */
object PerformanceAdvisor {

    /** How much load each tier takes before it starts to lag, compared with [weight] */
    private fun capacity(tier: DeviceTier): Int = when (tier) {
        DeviceTier.LOW -> 1
        DeviceTier.MID -> 2
        DeviceTier.HIGH -> 3
        DeviceTier.FLAGSHIP -> 4
    }

    fun recommended(profile: DeviceProfile): RecommendedSettings {
        val upscaler = if (profile.isAdreno) UpscalerMode.GSR else UpscalerMode.ASR
        return when (profile.tier) {
            // FPS unlimited everywhere (260 = Minecraft's "Unlimited"): the game's own default caps
            // 26.x at 120, and a cap only ever lowers what the phone could show
            DeviceTier.FLAGSHIP -> RecommendedSettings(85, upscaler, 16, 10, 260, 300, 4)
            DeviceTier.HIGH -> RecommendedSettings(75, upscaler, 12, 8, 260, 180, 3)
            DeviceTier.MID -> RecommendedSettings(67, upscaler, 8, 6, 260, 90, 2)
            DeviceTier.LOW -> RecommendedSettings(50, upscaler, 6, 5, 260, 40, 1)
        }
    }

    /**
     * Rate a project from its platform categories.
     * @return null when the categories say nothing about performance
     */
    fun rate(profile: DeviceProfile, classes: PlatformClasses, categories: List<PlatformFilterCode>?): PerformanceRating? {
        val codes = categories.orEmpty()
        return when (classes) {
            PlatformClasses.MOD -> {
                if (codes.any { it == ModrinthModCategory.OPTIMIZATION || it == CurseForgeModCategory.PERFORMANCE }) {
                    PerformanceRating.BOOST
                } else null
            }
            PlatformClasses.MOD_PACK -> {
                if (codes.any { it == ModrinthModpackCategory.OPTIMIZATION } && codes.none { it.isHeavyPack() }) {
                    return PerformanceRating.BOOST
                }
                fromWeight(packWeight(codes), capacity(profile.tier))
            }
            PlatformClasses.SHADERS -> {
                val level = shaderWeight(codes) ?: return null
                fromWeight(level, recommended(profile).shaderLevel)
            }
            else -> null
        }
    }

    /** Rate a version that is already installed from the number of mods and shader packs in it */
    fun rateInstalled(profile: DeviceProfile, gameDir: File): PerformanceRating {
        val mods = File(gameDir, "mods").listFiles { file -> file.name.endsWith(".jar") }?.size ?: 0
        val comfortable = recommended(profile).comfortableMods
        return when {
            mods <= comfortable -> PerformanceRating.SMOOTH
            mods <= comfortable * 3 / 2 -> PerformanceRating.MAY_LAG
            else -> PerformanceRating.HEAVY
        }
    }

    private fun fromWeight(weight: Int, capacity: Int): PerformanceRating = when {
        weight <= capacity -> PerformanceRating.SMOOTH
        weight == capacity + 1 -> PerformanceRating.MAY_LAG
        else -> PerformanceRating.HEAVY
    }

    private fun PlatformFilterCode.isHeavyPack(): Boolean =
        this == ModrinthModpackCategory.KITCHEN_SINK || this == CurseForgeModpackCategory.EXTRA_LARGE

    /** 1 light .. 4 huge */
    private fun packWeight(codes: List<PlatformFilterCode>): Int {
        if (codes.any { it.isHeavyPack() }) return 4
        if (codes.any { it == ModrinthModpackCategory.LIGHTWEIGHT || it == CurseForgeModpackCategory.LIGHTWEIGHT }) return 1
        val big = codes.count {
            it == ModrinthModpackCategory.TECHNOLOGY || it == ModrinthModpackCategory.MAGIC ||
                    it == ModrinthModpackCategory.QUESTS || it == ModrinthModpackCategory.ADVENTURE ||
                    it == CurseForgeModpackCategory.TECHNOLOGY || it == CurseForgeModpackCategory.MAGIC ||
                    it == CurseForgeModpackCategory.QUESTS || it == CurseForgeModpackCategory.ADVENTURE ||
                    it == CurseForgeModpackCategory.EXPLORATION || it == CurseForgeModpackCategory.SCI_FI ||
                    it == CurseForgeModpackCategory.FTB
        }
        return when {
            big >= 3 -> 4
            big >= 1 -> 3
            codes.any { it == CurseForgeModpackCategory.VANILLA || it == CurseForgeModpackCategory.SKYBLOCK || it == CurseForgeModpackCategory.MINI_GAME } -> 1
            else -> 2
        }
    }

    /** Modrinth shader performance tags, null when the pack has none */
    private fun shaderWeight(codes: List<PlatformFilterCode>): Int? = when {
        codes.contains(ModrinthShadersCategory.SCREENSHOT) -> 5
        codes.contains(ModrinthShadersCategory.HIGH) -> 4
        codes.contains(ModrinthShadersCategory.MEDIUM) -> 3
        codes.contains(ModrinthShadersCategory.LOW) -> 2
        codes.contains(ModrinthShadersCategory.POTATO) -> 1
        codes.contains(ModrinthShadersCategory.PATH_TRACING) -> 5
        else -> null
    }
}
