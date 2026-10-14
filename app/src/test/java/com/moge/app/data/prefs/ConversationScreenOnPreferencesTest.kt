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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConversationScreenOnPreferencesTest {
    @Test fun `screen on defaults off and persists without changing other preferences`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        IsolatedSettingsStore(context).use { fixture ->
            val settings = fixture.repository
            val before = settings.current()
            assertFalse(before.keepScreenOnInConversation)
            settings.setKeepScreenOnInConversation(true)
            assertEquals(before.copy(keepScreenOnInConversation = true), SettingsRepository(fixture.store).current())
            settings.setKeepScreenOnInConversation(false)
            assertEquals(before, SettingsRepository(fixture.store).current())
        }
    }
}
