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
import com.moge.app.data.image.QuestionPhotoFiles
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
    private val pages = mockk<BookPageProcessor>()
    private val processor = QuestionPhotoPreprocessor(settings, pages, Dispatchers.Unconfined)

    @Test fun `chat bypasses even settings and disabled solve never inspects images`() = runBlocking {
        assertEquals(PhotoPreparationResult.SKIPPED, processor.prepare("unused", ConversationMode.CHAT))
        coVerify(exactly = 0) { settings.current() }
        coEvery { settings.current() } returns UserSettings(questionPhotoPreprocessing = false)
        assertEquals(PhotoPreparationResult.SKIPPED, processor.prepare("unused", ConversationMode.SOLVE))
        verify(exactly = 0) { pages.render(any()) }
    }

    @Test fun `enabled solve preserves model source and reuses full resolution presentation file`() = runBlocking {
        val original = temporary.newFile().apply { writeText("original") }
        val output = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        every { pages.render(original.path) } returns output
        assertEquals(PhotoPreparationResult.PROCESSED, processor.prepare(original.path, ConversationMode.SOLVE))
        assertEquals("original", original.readText())
        val display = QuestionPhotoFiles.displayPath(original.path)
        assertNotEquals(original.path, display)
        assertEquals(PhotoPreparationResult.PROCESSED, processor.prepare(original.path, ConversationMode.SOLVE))
        verify(exactly = 1) { pages.render(original.path) }
        val saved = BitmapFactory.decodeFile(display)
        assertEquals(80, saved.width)
        assertEquals(120, saved.height)
        assertEquals(Color.BLUE, saved.getPixel(40, 60))
        saved.recycle()
        assertTrue(output.isRecycled)
        // A subsequent loader/repository recreation needs no in-memory job to find the result.
        assertEquals(display, QuestionPhotoFiles.displayPath(original.path))
        val timestamp = original.lastModified()
        original.writeText("recrop!!")
        original.setLastModified(timestamp)
        assertEquals(original.path, QuestionPhotoFiles.displayPath(original.path))
    }

    @Test fun `stale queued version and recrop during render never publish an old result`() = runBlocking {
        val original = temporary.newFile().apply { writeText("before") }
        val oldHash = QuestionPhotoFiles.fingerprint(original.path)!!
        original.writeText("after!")
        assertEquals(PhotoPreparationResult.SKIPPED, processor.prepare(original.path, ConversationMode.SOLVE, oldHash))
        verify(exactly = 0) { pages.render(any()) }
        val output = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        every { pages.render(original.path) } answers {
            original.writeText("newest")
            output
        }
        val revision = QuestionPhotoFiles.revision(original.path)
        assertEquals(PhotoPreparationResult.SKIPPED, processor.prepare(original.path, ConversationMode.SOLVE))
        assertTrue(output.isRecycled)
        assertEquals(revision, QuestionPhotoFiles.revision(original.path))
        assertTrue(QuestionPhotoFiles.companions(original.path).isEmpty())
        assertEquals("newest", original.readText())
    }

    @Test fun `native failure and failed save retain original without leaving temporary files`() = runBlocking {
        val original = temporary.newFile().apply { writeText("original") }
        every { pages.render(original.path) } throws UnsatisfiedLinkError("native unavailable")
        assertEquals(PhotoPreparationResult.ORIGINAL_RETAINED, processor.prepare(original.path, ConversationMode.SOLVE))
        assertEquals("original", original.readText())
        val output = mockk<Bitmap>(relaxed = true) { every { compress(any(), any(), any()) } returns false }
        every { pages.render(original.path) } returns output
        assertEquals(PhotoPreparationResult.ORIGINAL_RETAINED, processor.prepare(original.path, ConversationMode.SOLVE))
        assertEquals("original", original.readText())
        assertEquals(listOf(original.name), temporary.root.listFiles()!!.map { it.name })
        verify(exactly = 1) { output.recycle() }
    }

    @Test fun `cancellation is propagated and never becomes fallback success`() = runBlocking {
        val original = temporary.newFile().apply { writeText("original") }
        every { pages.render(original.path) } throws CancellationException("leaving capture")
        try {
            processor.prepare(original.path, ConversationMode.SOLVE)
            fail("Cancellation must be propagated")
        } catch (_: CancellationException) { }
        assertEquals("original", original.readText())
        verify(exactly = 1) { pages.render(original.path) }
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
