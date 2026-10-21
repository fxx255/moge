package com.moge.app.runtime

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import com.moge.app.domain.ConversationMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class QuestionPhotoPreparationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = mockk<SettingsRepository> { coEvery { current() } returns UserSettings() }

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    object : CoroutineWorker(appContext, workerParameters) {
                        override suspend fun doWork(): Result = awaitCancellation()
                    }
            }).build())
    }

    @After fun teardown() {
        appScope.cancel()
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun jobs() = WorkManager.getInstance(context)
        .getWorkInfosByTag(QuestionPhotoPreparationWorker::class.java.name).get()

    @Test fun `handoff keeps one durable job while equal size recrop registers a new version`() {
        val file = temporary.newFile().apply { writeText("crop-A") }
        val queue = QuestionPhotoPreparation(context, settings, appScope)
        queue.enqueue(listOf(file.path), ConversationMode.SOLVE)
        val first = jobs().single()
        queue.enqueue(listOf(file.path, file.path), ConversationMode.SOLVE)
        assertEquals(first.id, jobs().single().id)
        val timestamp = file.lastModified()
        file.writeText("crop-B")
        file.setLastModified(timestamp)
        queue.enqueue(listOf(file.path), ConversationMode.SOLVE)
        assertEquals(2, jobs().size)
        // Work is owned by WorkManager, independently of the coroutine that registered it.
        appScope.cancel()
        assertTrue(jobs().none { it.state.isFinished })
    }

    @Test fun `chat bypasses settings and disabled solve creates no background job`() {
        val queue = QuestionPhotoPreparation(context, settings, appScope)
        queue.enqueue(listOf(File(temporary.root, "missing").path), ConversationMode.CHAT)
        coVerify(exactly = 0) { settings.current() }
        coEvery { settings.current() } returns UserSettings(questionPhotoPreprocessing = false)
        queue.enqueue(listOf(temporary.newFile().apply { writeText("photo") }.path), ConversationMode.SOLVE)
        assertTrue(jobs().isEmpty())
    }
}
