package com.moge.app.core

import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf

internal fun localizedTestText() = LocalizedText(
    ApplicationProvider.getApplicationContext(),
    mockk<SettingsRepository> { every { settings } returns flowOf(UserSettings()) },
    CoroutineScope(Dispatchers.Unconfined),
)
