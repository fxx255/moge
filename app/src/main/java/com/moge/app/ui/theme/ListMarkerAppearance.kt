package com.moge.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import com.moge.app.data.prefs.ListMarkerSettings

internal val LocalListMarkerSettings = staticCompositionLocalOf { ListMarkerSettings() }

internal fun listMarkerAccent(chalk: Boolean): Int = if (chalk) 0xFF9DB9AE.toInt() else 0xFF71867E.toInt()
