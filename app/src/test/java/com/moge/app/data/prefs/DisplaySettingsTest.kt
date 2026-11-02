package com.moge.app.data.prefs

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DisplaySettingsTest {
    @Test fun `old preferences keep the existing size and scale`() = runBlocking {
        IsolatedSettingsStore(ApplicationProvider.getApplicationContext()).use { store ->
            assertEquals(17, store.repository.current().bodyTextSizeSp)
            assertEquals(100, store.repository.current().uiScalePercent)
        }
    }

    @Test fun `body size and ui scale persist independently`() = runBlocking {
        IsolatedSettingsStore(ApplicationProvider.getApplicationContext()).use { store ->
            val repository = store.repository
            repository.setBodyTextSizeSp(24)
            repository.setUiScalePercent(150)
            repository.setNickname("小墨")
            assertEquals(24, SettingsRepository(store.store).current().bodyTextSizeSp)
            assertEquals(150, repository.current().uiScalePercent)
            repository.setUiScalePercent(75)
            assertEquals(24, repository.current().bodyTextSizeSp)
            assertEquals("小墨", repository.current().nickname)
            repository.setBodyTextSizeSp(17)
            assertEquals(75, repository.current().uiScalePercent)
        }
    }

    @Test fun `invalid persisted sizes are clamped and invalid writes are rejected`() = runBlocking {
        IsolatedSettingsStore(ApplicationProvider.getApplicationContext()).use { store ->
            for ((body, scale) in listOf(-1 to Int.MAX_VALUE, Int.MAX_VALUE to -1)) {
                store.store.edit {
                    it[intPreferencesKey("body_text_size_sp")] = body
                    it[intPreferencesKey("ui_scale_percent")] = scale
                }
                assertEquals(body.coerceIn(14..32), store.repository.current().bodyTextSizeSp)
                assertEquals(scale.coerceIn(75..200), store.repository.current().uiScalePercent)
                assertTrue(runCatching { store.repository.setBodyTextSizeSp(body) }.exceptionOrNull() is IllegalArgumentException)
                assertTrue(runCatching { store.repository.setUiScalePercent(scale) }.exceptionOrNull() is IllegalArgumentException)
            }
        }
    }
}
