package com.moge.app.data.prefs

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HistoryRetentionPreferencesTest {
    @Test fun `retention defaults and edited preferences persist independently`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsRepository(context)
        assertTrue(settings.current().historyAutoCleanupEnabled)
        assertEquals(7, settings.current().historyRetentionDays)
        settings.setHistoryRetentionDays(45)
        settings.setHistoryAutoCleanupEnabled(false)
        val restored = SettingsRepository(context).current()
        assertFalse(restored.historyAutoCleanupEnabled)
        assertEquals(45, restored.historyRetentionDays)
        assertTrue(File(context.filesDir, "datastore/settings.preferences_pb").length() > 0)
        assertTrue(runCatching { settings.setHistoryRetentionDays(0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { settings.setHistoryRetentionDays(366) }.exceptionOrNull() is IllegalArgumentException)
        settings.setHistoryAutoCleanupEnabled(true)
        assertTrue(settings.current().historyAutoCleanupEnabled)
        assertEquals(45, settings.current().historyRetentionDays)
    }
}
