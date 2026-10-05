package com.moge.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import com.moge.app.data.prefs.Appearance

val LocalPaperColors = staticCompositionLocalOf { PaperExtras }

// Slightly softer controls retain the paper theme's restrained geometry.
internal val MogeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** 按「外观」设置和系统深浅色决定是否用黑板主题。 */
fun usesChalk(appearance: Appearance, systemDark: Boolean): Boolean = when (appearance) {
    Appearance.SYSTEM -> systemDark
    Appearance.PAPER -> false
    Appearance.CHALK -> true
}

@Composable
fun MogeTheme(
    appearance: Appearance = Appearance.SYSTEM,
    content: @Composable () -> Unit,
) {
    val chalk = usesChalk(appearance, isSystemInDarkTheme())
    val motionEnabled = rememberPaperMotionEnabled()
    CompositionLocalProvider(
        LocalPaperColors provides if (chalk) ChalkExtras else PaperExtras,
        LocalPaperMotionEnabled provides motionEnabled,
    ) {
        MaterialTheme(
            colorScheme = if (chalk) ChalkColorScheme else PaperColorScheme,
            typography = MogeTypography,
            shapes = MogeShapes,
            content = content,
        )
    }
}

object MogeTheme {
    val motionEnabled: Boolean
        @Composable @ReadOnlyComposable get() = LocalPaperMotionEnabled.current
    val paper: PaperColors
        @Composable @ReadOnlyComposable get() = LocalPaperColors.current
}
