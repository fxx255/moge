package com.moge.app.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.image.decodePhotoBitmap
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.domain.ConversationMode
import com.moge.app.imaging.book.BookPageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QuestionPhotoDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory get() = File(context.filesDir, "photo-preprocess").apply { mkdirs() }

    @Test fun localOcrAndNativePreparationRunBeforeCroppingWhileChatAndOrdinaryPhotosStayUnchanged() = runBlocking {
        val settings = SettingsRepository(context)
        val enabled = settings.current().questionPhotoPreprocessing
        val classifier = QuestionPhotoClassifier()
        val processor = QuestionPhotoPreprocessor(settings, classifier, BookPageProcessor(), Dispatchers.IO)
        val file = File(directory, "synthetic-question.png")
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.rgb(225, 219, 204))
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 34f }
                drawText("已知 x + 1 = 3，求 x 的值。", 60f, 130f, text)
                drawText("1. 计算下列各题，并写出步骤。", 60f, 210f, text)
                drawText("A. 1    B. 2    C. 3    D. 4", 60f, 290f, text)
                drawText("Question: solve the equation.", 60f, 370f, text)
            }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        val original = file.readBytes()
        try {
            settings.setQuestionPhotoPreprocessing(true)
            val evidence = classifier.inspect(file.path)
            assertTrue("Bundled OCR should identify this exercise: ${evidence.text}", evidence.isQuestion)
            assertEquals(PhotoPreparationResult.SKIPPED, processor.prepare(file.path, ConversationMode.CHAT))
            assertArrayEquals(original, file.readBytes())
            settings.setQuestionPhotoPreprocessing(false)
            assertEquals(PhotoPreparationResult.SKIPPED, processor.prepare(file.path, ConversationMode.SOLVE))
            assertArrayEquals(original, file.readBytes())
            settings.setQuestionPhotoPreprocessing(true)
            val stages = mutableListOf<PhotoPreparationStage>()
            assertEquals(PhotoPreparationResult.PROCESSED, processor.prepare(file.path, ConversationMode.SOLVE, stages::add))
            assertEquals(listOf(PhotoPreparationStage.DETECTING, PhotoPreparationStage.PROCESSING), stages)
            val result = requireNotNull(decodePhotoBitmap(file.path))
            try {
                assertTrue(result.width >= 700 && result.height >= 900)
                assertTrue("Paper should become brighter", Color.red(result.getPixel(10, 10)) > 225)
            } finally { result.recycle() }
            val scenery = File(directory, "ordinary.png")
            val plain = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
            try {
                plain.eraseColor(Color.rgb(40, 110, 170))
                scenery.outputStream().use { plain.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { plain.recycle() }
            val before = scenery.readBytes()
            assertEquals(PhotoPreparationResult.SKIPPED, processor.prepare(scenery.path, ConversationMode.SOLVE))
            assertArrayEquals(before, scenery.readBytes())
        } finally { settings.setQuestionPhotoPreprocessing(enabled) }
    }

    /** Optional local photograph, supplied by the validation script rather than committed user data. */
    @Test fun suppliedBookPhotoRunsLocalDetectionAndProducesAReadableResult() = runBlocking {
        val file = File(directory, "input.jpg")
        assumeTrue(file.isFile)
        val settings = SettingsRepository(context)
        val enabled = settings.current().questionPhotoPreprocessing
        try {
            settings.setQuestionPhotoPreprocessing(true)
            val classifier = QuestionPhotoClassifier()
            val evidence = classifier.inspect(file.path)
            assertTrue("Question must be detected in the supplied textbook page", evidence.isQuestion)
            val output = File(directory, "book-result.jpg")
            file.copyTo(output, overwrite = true)
            val processor = QuestionPhotoPreprocessor(settings, classifier, BookPageProcessor(), Dispatchers.IO)
            assertEquals(PhotoPreparationResult.PROCESSED, processor.prepare(output.path, ConversationMode.SOLVE))
            val result = requireNotNull(decodePhotoBitmap(output.path))
            try { assertTrue(result.width > 500 && result.height > 500) } finally { result.recycle() }
        } finally { settings.setQuestionPhotoPreprocessing(enabled) }
    }
}
