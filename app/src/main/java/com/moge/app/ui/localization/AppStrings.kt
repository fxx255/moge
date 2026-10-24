package com.moge.app.ui.localization

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.view.ContextThemeWrapper
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.moge.app.data.prefs.AppLanguage
import java.util.Locale
import com.moge.app.core.TextResources

/** Resource-backed text can also be safely captured by event handlers and remember blocks. */
class AppStrings(private val context: Context) : TextResources {
    override fun text(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
}

// Android Dialog/Popup installs its own LocalContext. Keep the selected resources
// in a separate local so their children retain the app language as well.
private val LocalAppStrings = staticCompositionLocalOf<AppStrings?> { null }

fun localizedContext(context: Context, language: AppLanguage): Context {
    val config = Configuration(context.resources.configuration).apply {
        setLocales(LocaleList(Locale.forLanguageTag(language.tag)))
    }
    return ContextThemeWrapper(context, context.theme).apply { applyOverrideConfiguration(config) }
}

@Composable
fun rememberAppStrings(): AppStrings {
    LocalAppStrings.current?.let { return it }
    val context = LocalContext.current
    val config = LocalConfiguration.current
    return remember(context, config) { AppStrings(localizedContext(context, AppLanguage.SIMPLIFIED_CHINESE)) }
}

@Composable
fun appString(@StringRes id: Int, vararg args: Any): String = rememberAppStrings().text(id, *args)

/** Update resources in place: switching language must retain navigation and reading position. */
@Composable
fun AppLanguageProvider(language: AppLanguage, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val configuration = LocalConfiguration.current
    val context = remember(base, configuration, language) { localizedContext(base, language) }
    val strings = remember(context) { AppStrings(context) }
    CompositionLocalProvider(
        LocalAppStrings provides strings,
        LocalContext provides context,
        LocalConfiguration provides context.resources.configuration,
        content = content,
    )
}
