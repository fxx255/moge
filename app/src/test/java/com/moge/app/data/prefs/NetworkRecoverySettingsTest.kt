package com.moge.app.data.prefs

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.llm.RequestSnapshot
import com.moge.app.data.llm.SnapshotCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NetworkRecoverySettingsTest {
    @Test fun optionDefaultsOffAndIsPinnedInTheAttemptSnapshot() = runBlocking {
        IsolatedSettingsStore(ApplicationProvider.getApplicationContext()).use {
            assertFalse(it.repository.current().autoResumeOnNetworkLoss)
            it.repository.setAutoResumeOnNetworkLoss(true)
            val snapshot = RequestSnapshot(autoResumeOnNetworkLoss = it.repository.current().autoResumeOnNetworkLoss)
            it.repository.setAutoResumeOnNetworkLoss(false)
            assertTrue(SnapshotCodec.decode(SnapshotCodec.encode(snapshot))!!.toPolicy().autoResumeOnNetworkLoss)
            assertFalse(it.repository.current().autoResumeOnNetworkLoss)
            assertFalse(SnapshotCodec.decode("{}")!!.autoResumeOnNetworkLoss)
        }
    }
}
