package com.moge.app.ui.theme

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.moge.app.data.prefs.DisplaySettings
import kotlin.math.roundToInt

internal val LocalBodyTextSize = staticCompositionLocalOf { DisplaySettings.DEFAULT_BODY_TEXT_SIZE_SP.sp }

/** Scale layout and sp together, preserving the user's system font multiplier. */
@Composable
internal fun AppDisplayScale(bodyTextSizeSp: Int, uiScalePercent: Int, content: @Composable () -> Unit) {
    val original = LocalDensity.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val scale = uiScalePercent.coerceIn(DisplaySettings.uiScaleRange) / 100f
    val density = remember(original, scale) { Density(original.density * scale, original.fontScale) }
    val scaledConfiguration = remember(configuration, original, scale) {
        Configuration(configuration).apply {
            screenWidthDp = (configuration.screenWidthDp / scale).roundToInt()
            screenHeightDp = (configuration.screenHeightDp / scale).roundToInt()
            smallestScreenWidthDp = (configuration.smallestScreenWidthDp / scale).roundToInt()
            densityDpi = (configuration.densityDpi * scale).roundToInt()
            fontScale = original.fontScale
        }
    }
    // Native text selection, table padding and localized resources must use the
    // same density as Compose. The language provider also copies this configuration.
    val scaledContext = remember(context, scaledConfiguration) {
        ContextThemeWrapper(context, context.theme).apply { applyOverrideConfiguration(scaledConfiguration) }
    }
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalContext provides scaledContext,
        LocalConfiguration provides scaledConfiguration,
        LocalBodyTextSize provides bodyTextSizeSp.coerceIn(DisplaySettings.bodyTextSizeRange).sp,
        content = content,
    )
}
