package com.moge.app.data.prefs

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LanguageFontPreferencesTest {
    @Test fun oldAndUnknownPreferencesUseCompatibleDefaults() = runBlocking {
        IsolatedSettingsStore(ApplicationProvider.getApplicationContext()).use { store ->
            assertDefaults(store.repository.current())
            store.store.edit {
                it[stringPreferencesKey("interface_language")] = "unknown"
                it[stringPreferencesKey("answer_language")] = "unknown"
                it[stringPreferencesKey("font")] = "unknown"
            }
            assertDefaults(store.repository.current())
        }
    }

    @Test fun languagesAndFontPersistIndependentlyWithoutChangingOtherPreferences() = runBlocking {
        IsolatedSettingsStore(ApplicationProvider.getApplicationContext()).use { store ->
            val repository = store.repository
            repository.setNickname("小墨")
            repository.setAppearance(Appearance.CHALK)
            repository.setInterfaceLanguage(AppLanguage.JAPANESE)
            repository.setAnswerLanguage(AppLanguage.ENGLISH)
            repository.setFont(AppFont.WENKAI)
            val reread = SettingsRepository(store.store).current()
            assertEquals(AppLanguage.JAPANESE, reread.interfaceLanguage)
            assertEquals(AppLanguage.ENGLISH, reread.answerLanguage)
            assertEquals(AppFont.WENKAI, reread.font)
            assertEquals(Appearance.CHALK, reread.appearance)
            assertEquals("小墨", reread.nickname)
            repository.setInterfaceLanguage(AppLanguage.TRADITIONAL_CHINESE)
            assertEquals(reread.copy(interfaceLanguage = AppLanguage.TRADITIONAL_CHINESE), repository.current())
            repository.setFont(AppFont.SYSTEM)
            assertEquals(AppLanguage.ENGLISH, repository.current().answerLanguage)
        }
    }

    private fun assertDefaults(prefs: UserSettings) {
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, prefs.interfaceLanguage)
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, prefs.answerLanguage)
        assertEquals(AppFont.SYSTEM, prefs.font)
    }
}
