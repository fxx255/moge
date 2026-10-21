package com.moge.app.runtime

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.moge.app.data.image.PhotoCropEdit
import com.moge.app.data.image.PixelRegionDecoder
import com.moge.app.data.image.QuestionPhotoFiles
import com.moge.app.data.image.decodePhotoBitmap
import com.moge.app.data.image.writePhotoBitmap
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import com.moge.app.domain.ConversationMode
import com.moge.app.imaging.QuestionPhotoPreprocessor
import com.moge.app.imaging.book.BookPageProcessor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [PixelRegionDecoder::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuestionPhotoCropPreparationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = mockk<SettingsRepository> { coEvery { current() } returns UserSettings() }
    private val pages = mockk<BookPageProcessor>()
    private val processor = QuestionPhotoPreprocessor(settings, pages, Dispatchers.IO)
    private val entered = CompletableDeferred<Unit>()
    private val release = CountDownLatch(1)
    private lateinit var queue: QuestionPhotoPreparation
    private lateinit var work: WorkManager

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    object : CoroutineWorker(appContext, workerParameters) {
                        override suspend fun doWork(): Result {
                            finishQuestionPhotoPreparation(inputData.getString(QuestionPhotoPreparation.SOURCE)!!,
                                inputData.getString(QuestionPhotoPreparation.FINGERPRINT)!!,
                                inputData.preparedPhotoCrop(), processor,
                                publishChange = !inputData.getBoolean(QuestionPhotoPreparation.SNAPSHOT, false))
                            return Result.success()
                        }
                    }
            }).build())
        work = WorkManager.getInstance(context)
        queue = QuestionPhotoPreparation(context, settings, scope)
        every { pages.render(any()) } answers {
            entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS)) { "test render was not released" }
            val decoded = decodePhotoBitmap(firstArg(), preserveResolution = true)!!
            val mutable = try { decoded.copy(Bitmap.Config.ARGB_8888, true) } finally { decoded.recycle() }
            mutable.apply {
                // The mocked renderer skips OpenCV initialization; use the platform PNG writer.
                setHasAlpha(true)
                // An obvious presentation-only result, while retaining spatially distinct source colors.
                for (y in 0 until height) for (x in 0 until width) {
                    val old = getPixel(x, y)
                    setPixel(x, y, Color.rgb(Color.red(old), Color.green(old), 200))
                }
            }
        }
    }

    @After fun teardown() {
        release.countDown()
        runBlocking { delay(100) }
        scope.cancel()
        work.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun photo(): File = temporary.newFile("source.png").also { file ->
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 80) for (x in 0 until 120) bitmap.setPixel(x, y, Color.rgb(x, y, 0))
            writePhotoBitmap(file.path, bitmap)
        } finally { bitmap.recycle() }
    }

    private suspend fun awaitDisplay(file: File): Bitmap = try {
        withTimeout(10_000) {
            while (QuestionPhotoFiles.displayPath(file.path) == file.path) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
            BitmapFactory.decodeFile(QuestionPhotoFiles.displayPath(file.path))
        }
    } catch (timeout: TimeoutCancellationException) {
        throw AssertionError("Workers did not publish: ${work.getWorkInfosByTag(QuestionPhotoPreparationWorker::class.java.name).get()}; ${ShadowLog.getLogs()}", timeout)
    }

    @Test fun `confirm while native work is blocked accepts original then publishes the rotated crop once`() = runBlocking {
        val file = photo()
        val before = file.readBytes()
        queue.beginCrop(file.path, ConversationMode.SOLVE)
        withTimeout(5_000) { entered.await() }
        val edit = PhotoCropEdit(1, .25f, .25f, .5f, .5f)
        // Confirm and draft handoff must finish while native processing is still blocked.
        withTimeout(3_000) { queue.saveCrop(file.path, ConversationMode.SOLVE, edit) }
        queue.endCrop(file.path)
        queue.enqueue(listOf(file.path), ConversationMode.SOLVE)
        val modelBytes = file.readBytes()
        assertFalse(before.contentEquals(modelBytes))
        assertEquals(file.path, QuestionPhotoFiles.displayPath(file.path))
        val original = BitmapFactory.decodeFile(file.path)
        try {
            assertEquals(40, original.width)
            assertEquals(60, original.height)
            assertEquals(Color.rgb(30, 59, 0), original.getPixel(0, 0))
        } finally { original.recycle() }
        release.countDown()
        val display = awaitDisplay(file)
        try {
            assertEquals(40, display.width)
            assertEquals(60, display.height)
            assertEquals(Color.rgb(30, 59, 200), display.getPixel(0, 0))
        } finally { display.recycle() }
        assertArrayEquals("Background completion must not alter model input", modelBytes, file.readBytes())
        assertTrue(QuestionPhotoFiles.revision(file.path) > 0)
        verify(exactly = 1) { pages.render(any()) }
        withTimeout(5_000) {
            while (work.getWorkInfosByTag(QuestionPhotoPreparationWorker::class.java.name).get().any { !it.state.isFinished }) delay(10)
        }
        assertEquals(2, work.getWorkInfosByTag(QuestionPhotoPreparationWorker::class.java.name).get().size)
        assertTrue(temporary.root.listFiles()!!.none { it.name.startsWith(".crop-") })
    }

    @Test fun `a newer recrop supersedes an unfinished accepted version without losing the latest update`() = runBlocking {
        val file = photo()
        queue.beginCrop(file.path, ConversationMode.SOLVE)
        withTimeout(5_000) { entered.await() }
        queue.saveCrop(file.path, ConversationMode.SOLVE, PhotoCropEdit(0, .25f, 0f, .5f, 1f))
        val obsolete = QuestionPhotoFiles.fingerprint(file.path)!!
        queue.beginCrop(file.path, ConversationMode.SOLVE)
        queue.saveCrop(file.path, ConversationMode.SOLVE, PhotoCropEdit(0, 0f, .25f, 1f, .5f))
        val modelBytes = file.readBytes()
        release.countDown()
        val display = awaitDisplay(file)
        try {
            assertEquals(60, display.width)
            assertEquals(40, display.height)
            assertEquals(Color.rgb(30, 20, 200), display.getPixel(0, 0))
        } finally { display.recycle() }
        assertFalse(QuestionPhotoFiles.output(file.path, obsolete).exists())
        assertArrayEquals(modelBytes, file.readBytes())
    }

    @Test fun `canceling a crop and discarding its source cannot publish a ghost result`() = runBlocking {
        val file = photo()
        queue.beginCrop(file.path, ConversationMode.SOLVE)
        withTimeout(5_000) { entered.await() }
        queue.endCrop(file.path)
        file.delete()
        release.countDown()
        withTimeout(5_000) {
            while (work.getWorkInfosByTag(QuestionPhotoPreparationWorker::class.java.name).get().any { !it.state.isFinished }) delay(10)
        }
        assertEquals(0L, QuestionPhotoFiles.revision(file.path))
        assertTrue(QuestionPhotoFiles.companions(file.path).isEmpty())
        assertFalse(file.exists())
    }

    @Test fun `canceling the UI waiter does not cancel accepted crop saving or its durable handoff`() = runBlocking {
        val file = photo()
        val allowSnapshot = CompletableDeferred<Unit>()
        coEvery { settings.current() } coAnswers { allowSnapshot.await(); UserSettings() }
        queue.beginCrop(file.path, ConversationMode.SOLVE)
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            queue.saveCrop(file.path, ConversationMode.SOLVE, PhotoCropEdit(0, .25f, .25f, .5f, .5f))
        }
        waiter.cancelAndJoin()
        queue.endCrop(file.path)
        allowSnapshot.complete(Unit)
        withTimeout(5_000) { entered.await() }
        release.countDown()
        val display = awaitDisplay(file)
        try {
            assertEquals(60, display.width)
            assertEquals(40, display.height)
            assertEquals(Color.rgb(30, 20, 200), display.getPixel(0, 0))
        } finally { display.recycle() }
        val original = BitmapFactory.decodeFile(file.path)
        try { assertEquals(Color.rgb(30, 20, 0), original.getPixel(0, 0)) } finally { original.recycle() }
    }

    @Test fun `chat and disabled preprocessing save normally without making source copies or jobs`() = runBlocking {
        val file = photo()
        queue.beginCrop(file.path, ConversationMode.CHAT)
        queue.saveCrop(file.path, ConversationMode.CHAT, PhotoCropEdit())
        coVerify(exactly = 0) { settings.current() }
        coEvery { settings.current() } returns UserSettings(questionPhotoPreprocessing = false)
        queue.beginCrop(file.path, ConversationMode.SOLVE)
        queue.saveCrop(file.path, ConversationMode.SOLVE, PhotoCropEdit(0, 0f, 0f, .5f, 1f))
        assertEquals(listOf(file.name), temporary.root.listFiles()!!.map { it.name })
        assertTrue(work.getWorkInfosByTag(QuestionPhotoPreparationWorker::class.java.name).get().isEmpty())
        verify(exactly = 0) { pages.render(any()) }
    }
}
