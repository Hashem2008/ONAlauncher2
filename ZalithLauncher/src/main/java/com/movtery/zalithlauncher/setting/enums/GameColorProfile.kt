package com.movtery.zalithlauncher.setting.enums

import com.movtery.zalithlauncher.R

/**
 * ONA: colour look applied on top of the game picture, like a camera picture profile
 */
enum class GameColorProfile(
    val nameRes: Int,
    val contrast: Float,
    val saturation: Float,
    /** -1..1, added to every channel */
    val lift: Float,
    /** red and blue gain, a warm look pushes red up and blue down */
    val red: Float = 1f,
    val blue: Float = 1f
) {
    NATURAL(R.string.game_color_profile_natural, 1f, 1f, 0f),
    /** Puts back the punch a washed out wrapper loses */
    DEEP(R.string.game_color_profile_deep, 1.18f, 1.12f, -0.03f),
    VIVID(R.string.game_color_profile_vivid, 1.1f, 1.4f, 0f),
    /** Flat, low contrast, lifted shadows: like Log on a phone camera */
    LOG(R.string.game_color_profile_log, 0.72f, 0.78f, 0.07f),
    CINEMATIC(R.string.game_color_profile_cinematic, 1.12f, 0.9f, -0.02f, red = 1.06f, blue = 0.92f),
    COOL(R.string.game_color_profile_cool, 1.05f, 1.05f, 0f, red = 0.94f, blue = 1.08f)
}
