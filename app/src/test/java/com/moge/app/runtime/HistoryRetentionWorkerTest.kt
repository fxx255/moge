package com.moge.app.runtime

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HistoryRetentionWorkerTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }
    @After fun teardown() { WorkManagerTestInitHelper.closeWorkDatabase() }

    @Test fun `repeated startup keeps one offline periodic maintenance job`() {
        HistoryRetentionWorker.schedule(context)
        val workManager = WorkManager.getInstance(context)
        val first = workManager.getWorkInfosForUniqueWork(HistoryRetentionWorker.WORK_NAME).get().single()
        HistoryRetentionWorker.schedule(context)
        val again = workManager.getWorkInfosForUniqueWork(HistoryRetentionWorker.WORK_NAME).get().single()
        assertEquals(first.id, again.id)
        assertEquals(WorkInfo.State.ENQUEUED, again.state)
        assertEquals(TimeUnit.HOURS.toMillis(12), again.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(TimeUnit.HOURS.toMillis(12), again.initialDelayMillis)
        assertEquals(NetworkType.NOT_REQUIRED, again.constraints.requiredNetworkType)
    }

    @Test fun `turning off cancels periodic work and turning on schedules again`() {
        val workManager = WorkManager.getInstance(context)
        HistoryRetentionWorker.schedule(context)
        HistoryRetentionWorker.schedule(context, enabled = false)
        assertTrue(workManager.getWorkInfosForUniqueWork(HistoryRetentionWorker.WORK_NAME).get().all { it.state.isFinished })
        HistoryRetentionWorker.schedule(context, enabled = true)
        assertEquals(1, workManager.getWorkInfosForUniqueWork(HistoryRetentionWorker.WORK_NAME).get().count { !it.state.isFinished })
    }
}
