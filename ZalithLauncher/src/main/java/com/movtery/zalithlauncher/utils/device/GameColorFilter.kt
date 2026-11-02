package com.movtery.zalithlauncher.utils.device

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.widget.FrameLayout
import com.movtery.zalithlauncher.setting.enums.GameColorProfile

/**
 * ONA: colour grading of the game picture, without touching the game or the renderer.
 *
 * It used to be set as the TextureView layer paint, but a colour filter on a TextureView
 * layer goes through the external (OES) texture path of HWUI, which draws pure black on
 * several drivers (Samsung / Adreno on Android 15+ among them) as soon as the paint changes.
 * Now the grade is either done inside the upscaler pass, or by [GradeFrame] with a plain
 * saveLayer around the TextureView, which every driver handles.
 */
object GameColorFilter {
    /**
     * @param brightness -50..50
     * @param contrast 50..150 (percent)
     * @param saturation 0..200 (percent)
     * @return null when nothing changes, so the picture keeps its fast path
     */
    fun matrix(profile: GameColorProfile, brightness: Int, contrast: Int, saturation: Int): ColorMatrix? {
        if (profile == GameColorProfile.NATURAL && brightness == 0 && contrast == 100 && saturation == 100) return null
        val totalContrast = profile.contrast * contrast / 100f
        val totalSaturation = profile.saturation * saturation / 100f
        val offset = (profile.lift + brightness / 100f) * 255f

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
        return matrix
    }

    fun paint(profile: GameColorProfile, brightness: Int, contrast: Int, saturation: Int): Paint? {
        val matrix = matrix(profile, brightness, contrast, saturation) ?: return null
        return Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
    }

    /** The same grade as a column major GL mat4 working on 0..1 colours, for the upscaler */
    fun glMatrix(profile: GameColorProfile, brightness: Int, contrast: Int, saturation: Int): FloatArray? {
        val cm = matrix(profile, brightness, contrast, saturation)?.array ?: return null
        val gl = FloatArray(16)
        for (row in 0 until 3) {
            for (col in 0 until 3) gl[col * 4 + row] = cm[row * 5 + col]
            gl[12 + row] = cm[row * 5 + 4] / 255f
        }
        gl[15] = 1f
        return gl
    }

    /**
     * Holds the game TextureView and grades whatever it draws with an offscreen layer.
     */
    class GradeFrame(context: Context) : FrameLayout(context) {
        var gradePaint: Paint? = null
            set(value) {
                field = value
                invalidate()
            }

        override fun dispatchDraw(canvas: Canvas) {
            val paint = gradePaint
            if (paint == null) {
                super.dispatchDraw(canvas)
                return
            }
            val save = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), paint)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
        }
    }
}
