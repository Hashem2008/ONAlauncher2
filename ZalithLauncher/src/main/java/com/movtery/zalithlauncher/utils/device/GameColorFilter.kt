package com.movtery.zalithlauncher.utils.device

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.movtery.zalithlauncher.setting.enums.GameColorProfile

/**
 * ONA: builds the paint the game TextureView is composited with, so colours can be
 * graded live without touching the game or the renderer.
 */
object GameColorFilter {
    /**
     * @param brightness -50..50
     * @param contrast 50..150 (percent)
     * @param saturation 0..200 (percent)
     * @return null when nothing changes, so the view keeps its fast path
     */
    fun paint(profile: GameColorProfile, brightness: Int, contrast: Int, saturation: Int): Paint? {
        val totalContrast = profile.contrast * contrast / 100f
        val totalSaturation = profile.saturation * saturation / 100f
        val offset = (profile.lift + brightness / 100f) * 255f
        if (profile == GameColorProfile.NATURAL && brightness == 0 && contrast == 100 && saturation == 100) return null

        val matrix = ColorMatrix().apply { setSaturation(totalSaturation) }
        // contrast around mid grey, then brightness / lift
        val translate = 127.5f * (1f - totalContrast) + offset
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    totalContrast * profile.red, 0f, 0f, 0f, translate,
                    0f, totalContrast, 0f, 0f, translate,
                    0f, 0f, totalContrast * profile.blue, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        return Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
    }
}
