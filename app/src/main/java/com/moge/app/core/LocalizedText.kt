package com.moge.app.core

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import com.moge.app.data.prefs.AppLanguage
import com.moge.app.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Non-Compose presentation messages use the saved interface language, never the answer language. */
interface TextResources {
    fun text(@StringRes id: Int, vararg args: Any): String
}

@Singleton
class LocalizedText @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) : TextResources {
    private val language = settings.settings.map { it.interfaceLanguage }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, AppLanguage.SIMPLIFIED_CHINESE)

    override fun text(@StringRes id: Int, vararg args: Any): String {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(language.value.tag)))
        }
        val resources = context.createConfigurationContext(configuration).resources
        return if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)
    }
}
