package com.moge.app.imaging

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.prefs.IsolatedSettingsStore
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import com.moge.app.domain.ConversationMode
import com.moge.app.imaging.book.BookPageProcessor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuestionPhotoPreprocessorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val settings = mockk<SettingsRepository> {
        coEvery { current() } returns UserSettings()
    }
    private val classifier = mockk<QuestionPhotoClassifier>()
    private val pages = mockk<BookPageProcessor>()
    private val processor = QuestionPhotoPreprocessor(settings, classifier, pages, Dispatchers.Unconfined)
    private val question = QuestionPhotoEvidence("已知 x+1=3，求 x 的值。", listOf(TextRegion(.1f, .1f, .8f, .8f)))

    @Test fun `chat bypasses even settings and disabled solve never inspects images`() = runBlocking {
        assertEquals(PhotoPreparationResult.SKIPPED, processor.prepare("unused", ConversationMode.CHAT))
        coVerify(exactly = 0) { settings.current() }
        coEvery { settings.current() } returns UserSettings(questionPhotoPreprocessing = false)
        assertEquals(PhotoPreparationResult.SKIPPED, processor.prepare("unused", ConversationMode.SOLVE))
        coVerify(exactly = 0) { classifier.inspect(any()) }
        verify(exactly = 0) { pages.render(any(), any()) }
    }

    @Test fun `ordinary photo keeps exact original bytes`() = runBlocking {
        val original = temporary.newFile().apply { writeText("original") }
        coEvery { classifier.inspect(original.path) } returns QuestionPhotoEvidence("美好的一天", question.regions)
        assertEquals(PhotoPreparationResult.SKIPPED, processor.prepare(original.path, ConversationMode.SOLVE))
        assertEquals("original", original.readText())
        verify(exactly = 0) { pages.render(any(), any()) }
    }

    @Test fun `accepted question saves complete upright result and reports ordered stages`() = runBlocking {
        val original = temporary.newFile().apply { writeText("original") }
        coEvery { classifier.inspect(original.path) } returns question
        val output = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        every { pages.render(original.path, question) } returns output
        val stages = mutableListOf<PhotoPreparationStage>()
        assertEquals(PhotoPreparationResult.PROCESSED, processor.prepare(original.path, ConversationMode.SOLVE, stages::add))
        assertEquals(listOf(PhotoPreparationStage.DETECTING, PhotoPreparationStage.PROCESSING), stages)
        val saved = BitmapFactory.decodeFile(original.path)
        assertEquals(80, saved.width)
        assertEquals(120, saved.height)
        assertEquals(Color.BLUE, saved.getPixel(40, 60))
        saved.recycle()
        assertTrue(output.isRecycled)
    }

    @Test fun `native failure and failed save retain original without leaving temporary files`() = runBlocking {
        val original = temporary.newFile().apply { writeText("original") }
        coEvery { classifier.inspect(original.path) } returns question
        every { pages.render(original.path, question) } throws UnsatisfiedLinkError("native unavailable")
        assertEquals(PhotoPreparationResult.ORIGINAL_RETAINED, processor.prepare(original.path, ConversationMode.SOLVE))
        assertEquals("original", original.readText())
        val output = mockk<Bitmap>(relaxed = true) { every { compress(any(), any(), any()) } returns false }
        every { pages.render(original.path, question) } returns output
        assertEquals(PhotoPreparationResult.ORIGINAL_RETAINED, processor.prepare(original.path, ConversationMode.SOLVE))
        assertEquals("original", original.readText())
        assertEquals(listOf(original.name), temporary.root.listFiles()!!.map { it.name })
        verify(exactly = 1) { output.recycle() }
    }

    @Test fun `cancellation is propagated and never becomes fallback success`() = runBlocking {
        val original = temporary.newFile().apply { writeText("original") }
        coEvery { classifier.inspect(original.path) } throws CancellationException("leaving capture")
        try {
            processor.prepare(original.path, ConversationMode.SOLVE)
            fail("Cancellation must be propagated")
        } catch (_: CancellationException) { }
        assertEquals("original", original.readText())
        verify(exactly = 0) { pages.render(any(), any()) }
    }

    @Test fun `preprocessing defaults on and preference persists across repository recreation`() = runBlocking {
        IsolatedSettingsStore(ApplicationProvider.getApplicationContext()).use { store ->
            val before = store.repository.current()
            assertTrue(before.questionPhotoPreprocessing)
            store.repository.setQuestionPhotoPreprocessing(false)
            assertEquals(before.copy(questionPhotoPreprocessing = false), SettingsRepository(store.store).current())
            store.repository.setQuestionPhotoPreprocessing(true)
            assertEquals(before, SettingsRepository(store.store).current())
        }
    }
}
