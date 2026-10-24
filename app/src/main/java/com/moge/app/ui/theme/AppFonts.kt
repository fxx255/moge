package com.moge.app.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.res.ResourcesCompat
import com.moge.app.R
import com.moge.app.data.prefs.AppFont

val LocalAppTypeface = staticCompositionLocalOf { Typeface.DEFAULT }

internal fun AppFont.resource(): Int? = when (this) {
    AppFont.SYSTEM -> null
    AppFont.WENKAI -> R.font.wenkai
    AppFont.SOURCE_SERIF -> R.font.source_serif
    AppFont.ROUNDED -> R.font.rounded
}

/** ResourcesCompat caches font files; Compose and native Markdown share the same face. */
internal fun appTypeface(context: Context, font: AppFont): Typeface =
    font.resource()?.let { ResourcesCompat.getFont(context, it) } ?: Typeface.DEFAULT

@Composable
internal fun rememberAppTypeface(font: AppFont): Typeface {
    val context = LocalContext.current
    return remember(context, font) { appTypeface(context, font) }
}

internal fun appFontFamily(typeface: Typeface): FontFamily = FontFamily(typeface)
