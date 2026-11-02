package com.movtery.zalithlauncher.setting.enums

import com.movtery.zalithlauncher.R

/**
 * ONA: how the game picture, rendered below screen resolution, is scaled up to the screen
 */
enum class UpscalerMode(val nameRes: Int) {
    /** Plain bilinear scaling done by the system compositor (no extra pass) */
    OFF(R.string.ona_upscaler_off),
    /** GSR on Adreno, ASR on every other GPU */
    AUTO(R.string.ona_upscaler_auto),
    /** Edge directed 12 tap single pass upscaler tuned for Adreno, in the spirit of Snapdragon GSR */
    GSR(R.string.ona_upscaler_gsr),
    /** Bicubic upscale plus contrast adaptive sharpening, cheap on every ARM GPU (Mali, Xclipse, PowerVR, Adreno) */
    ASR(R.string.ona_upscaler_asr)
}
