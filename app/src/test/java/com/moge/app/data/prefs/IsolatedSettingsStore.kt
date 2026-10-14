package com.moge.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.util.UUID

/** Each test owns its file and coroutine scope; Android's process-wide delegate is not shared. */
internal class IsolatedSettingsStore(context: Context) : AutoCloseable {
    val file = File(context.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    val repository = SettingsRepository(store)
    override fun close() { scope.cancel() }
}
