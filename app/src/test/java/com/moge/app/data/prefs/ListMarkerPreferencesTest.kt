package com.moge.app.data.prefs

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
class ListMarkerPreferencesTest {
    @Test fun existingInstallationsKeepOctagonsAndEveryStylePersistsWithoutChangingDisplaySettings() = runBlocking {
        IsolatedSettingsStore(ApplicationProvider.getApplicationContext()).use { store ->
            assertEquals(ListMarkerSettings(), store.repository.current().listMarkers)
            store.repository.setBodyTextSizeSp(24)
            store.repository.setUiScalePercent(150)
            for (style in ListMarkerStyle.entries) {
                val value = ListMarkerSettings(style, sizePercent = 130, strokePercent = 85, indentPercent = 110)
                store.repository.setListMarkers(value)
                val restored = SettingsRepository(store.store).current()
                assertEquals(value, restored.listMarkers)
                assertEquals(24, restored.bodyTextSizeSp)
                assertEquals(150, restored.uiScalePercent)
            }
            store.repository.setListMarkers(ListMarkerSettings())
            assertEquals(ListMarkerSettings(), store.repository.current().listMarkers)
        }
    }

    @Test fun invalidSavedValuesFallBackAndInvalidWritesDoNotPartiallyChangeSettings() = runBlocking {
        IsolatedSettingsStore(ApplicationProvider.getApplicationContext()).use { store ->
            store.store.edit {
                it[stringPreferencesKey("list_marker_style")] = "future-style"
                it[intPreferencesKey("list_marker_size_percent")] = Int.MAX_VALUE
                it[intPreferencesKey("list_marker_stroke_percent")] = -100
                it[intPreferencesKey("list_marker_indent_percent")] = Int.MIN_VALUE
            }
            val restored = store.repository.current().listMarkers
            assertEquals(ListMarkerStyle.OCTAGON, restored.style)
            assertEquals(ListMarkerSettings.sizeRange.last, restored.sizePercent)
            assertEquals(ListMarkerSettings.strokeRange.first, restored.strokePercent)
            assertEquals(ListMarkerSettings.indentRange.first, restored.indentPercent)
            for (invalid in listOf(restored.copy(sizePercent = 0), restored.copy(strokePercent = 999), restored.copy(indentPercent = -1))) {
                assertTrue(runCatching { store.repository.setListMarkers(invalid) }.exceptionOrNull() is IllegalArgumentException)
                assertEquals(restored, store.repository.current().listMarkers)
            }
        }
    }
}
