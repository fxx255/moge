package com.moge.app.data.prefs

/** Display preferences are independent of the device density and system font setting. */
object DisplaySettings {
    const val DEFAULT_BODY_TEXT_SIZE_SP = 17
    const val DEFAULT_UI_SCALE_PERCENT = 100
    val bodyTextSizeRange = 14..32
    val uiScaleRange = 75..200
    val bodyTextSizes = listOf(14, 16, 17, 18, 20, 22, 24, 28, 32)
    val uiScalePercents = listOf(75, 85, 100, 110, 125, 150, 175, 200)
}
