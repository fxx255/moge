package com.moge.app.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.moge.app.data.image.decodePhotoBitmap
import com.moge.app.data.image.QuestionPhotoFiles
import com.moge.app.data.image.PhotoCropEdit
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.domain.ConversationMode
import com.moge.app.imaging.book.BookPageProcessor
import com.moge.app.runtime.QuestionPhotoPreparation
import com.moge.app.runtime.QuestionPhotoPreparationWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QuestionPhotoDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory get() = File(context.filesDir, "photo-preprocess").apply { mkdirs() }

    @Test fun cropPrewarmingHandsOffToRealWorkerAndPublishesOnlyLatestSelection() = runBlocking {
        val settings = SettingsRepository(context)
        val enabled = settings.current().questionPhotoPreprocessing
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val queue = QuestionPhotoPreparation(context, settings, scope)
        val folder = File(directory, "crop-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val file = File(folder, "source.png")
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(225, 219, 204))
            Canvas(bitmap).drawText("Question x + 1 = 3", 380f, 400f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 40f })
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val manager = WorkManager.getInstance(context)
        val workerTag = QuestionPhotoPreparationWorker::class.java.name
        val previous = manager.getWorkInfosByTag(workerTag).get().map { it.id }.toSet()
        try {
            settings.setQuestionPhotoPreprocessing(true)
            queue.beginCrop(file.path, ConversationMode.SOLVE)
            queue.saveCrop(file.path, ConversationMode.SOLVE, PhotoCropEdit(1, .25f, .25f, .5f, .5f))
            queue.endCrop(file.path)
            val obsolete = QuestionPhotoFiles.fingerprint(file.path)!!
            queue.beginCrop(file.path, ConversationMode.SOLVE)
            queue.saveCrop(file.path, ConversationMode.SOLVE, PhotoCropEdit(0, 0f, .25f, 1f, .5f))
            queue.endCrop(file.path)
            queue.enqueue(listOf(file.path), ConversationMode.SOLVE)
            val modelInput = file.readBytes()
            withTimeout(30_000) {
                while (QuestionPhotoFiles.displayPath(file.path) == file.path) delay(50)
                while (manager.getWorkInfosByTag(workerTag).get().any { it.id !in previous && !it.state.isFinished }) delay(50)
            }
            assertArrayEquals("Background completion must preserve the final model attachment", modelInput, file.readBytes())
            val display = requireNotNull(decodePhotoBitmap(QuestionPhotoFiles.displayPath(file.path)))
            try {
                assertEquals(400, display.width)
                assertEquals(300, display.height)
                assertTrue(Color.red(display.getPixel(10, 10)) > 225)
            } finally { display.recycle() }
            assertTrue("Photo update missing: path=${file.path}, canonical=${file.canonicalPath}, " +
                "pathRevision=${QuestionPhotoFiles.revision(file.path)}, canonicalRevision=${QuestionPhotoFiles.revision(file.canonicalPath)}",
                QuestionPhotoFiles.revision(file.path) > 0)
            assertNotEquals(QuestionPhotoFiles.output(file.path, obsolete).path, QuestionPhotoFiles.displayPath(file.path))
            assertTrue("Consumed crop snapshots should be removed", folder.listFiles()!!.none { it.name.startsWith(".crop-") })
        } finally {
            manager.getWorkInfosByTag(workerTag).get().filter { it.id !in previous }.forEach {
                manager.cancelWorkById(it.id).result.get()
            }
            scope.cancel()
            settings.setQuestionPhotoPreprocessing(enabled)
            folder.listFiles()?.forEach { it.delete() }
            folder.delete()
        }
    }

    @Test fun workManagerRunsColorCleanupWithoutChangingTheModelAttachment() = runBlocking {
        val settings = SettingsRepository(context)
        val enabled = settings.current().questionPhotoPreprocessing
        val file = File(directory, "worker-${java.util.UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(225, 219, 204))
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val original = file.readBytes()
        val work = OneTimeWorkRequestBuilder<QuestionPhotoPreparationWorker>().setInputData(workDataOf(
            QuestionPhotoPreparation.SOURCE to file.canonicalPath,
            QuestionPhotoPreparation.FINGERPRINT to QuestionPhotoFiles.fingerprint(file.path)!!,
        )).build()
        val workManager = WorkManager.getInstance(context)
        try {
            settings.setQuestionPhotoPreprocessing(true)
            workManager.enqueue(work).result.get()
            withTimeout(20_000) {
                while (QuestionPhotoFiles.displayPath(file.path) == file.path) delay(50)
            }
            assertArrayEquals(original, file.readBytes())
            val result = requireNotNull(decodePhotoBitmap(QuestionPhotoFiles.displayPath(file.path)))
            try {
                assertEquals(400, result.width)
                assertEquals(600, result.height)
                assertTrue(Color.red(result.getPixel(200, 300)) > 225)
            } finally { result.recycle() }
        } finally {
            workManager.cancelWorkById(work.id).result.get()
            settings.setQuestionPhotoPreprocessing(enabled)
            QuestionPhotoFiles.discard(file.path)
            file.delete()
        }
    }

    @Test fun enabledSolveProcessesAllPhotosWhileChatAndDisabledSolveKeepOriginals() = runBlocking {
        val settings = SettingsRepository(context)
        val enabled = settings.current().questionPhotoPreprocessing
        val processor = QuestionPhotoPreprocessor(settings, BookPageProcessor(), Dispatchers.IO)
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
            assertEquals(PhotoPreparationResult.SKIPPED, processor.prepare(file.path, ConversationMode.CHAT))
            assertArrayEquals(original, file.readBytes())
            settings.setQuestionPhotoPreprocessing(false)
            assertEquals(PhotoPreparationResult.SKIPPED, processor.prepare(file.path, ConversationMode.SOLVE))
            assertArrayEquals(original, file.readBytes())
            settings.setQuestionPhotoPreprocessing(true)
            assertEquals(PhotoPreparationResult.PROCESSED, processor.prepare(file.path, ConversationMode.SOLVE))
            assertArrayEquals("Model input must remain untouched", original, file.readBytes())
            val display = QuestionPhotoFiles.displayPath(file.path)
            assertNotEquals(file.path, display)
            val result = requireNotNull(decodePhotoBitmap(display))
            try {
                assertEquals(800, result.width)
                assertEquals(1000, result.height)
                assertTrue("Paper should become brighter", Color.red(result.getPixel(10, 10)) > 225)
            } finally { result.recycle() }
            val scenery = File(directory, "ordinary.png")
            val plain = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
            try {
                plain.eraseColor(Color.rgb(40, 110, 170))
                scenery.outputStream().use { plain.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { plain.recycle() }
            assertEquals("Even a photo without question text is processed", PhotoPreparationResult.PROCESSED,
                processor.prepare(scenery.path, ConversationMode.SOLVE))
            val processedScenery = requireNotNull(decodePhotoBitmap(QuestionPhotoFiles.displayPath(scenery.path)))
            try {
                assertEquals(400, processedScenery.width)
                assertEquals(600, processedScenery.height)
            } finally { processedScenery.recycle() }
        } finally { settings.setQuestionPhotoPreprocessing(enabled) }
    }

    /** Optional local photograph, supplied by the validation script rather than committed user data. */
    @Test fun suppliedCroppedBookPhotoKeepsOriginalAndProducesSeparateDisplay() = runBlocking {
        val file = File(directory, "input.jpg")
        assumeTrue(file.isFile)
        val settings = SettingsRepository(context)
        val enabled = settings.current().questionPhotoPreprocessing
        try {
            settings.setQuestionPhotoPreprocessing(true)
            val output = File(directory, "book-result.jpg")
            file.copyTo(output, overwrite = true)
            val processor = QuestionPhotoPreprocessor(settings, BookPageProcessor(), Dispatchers.IO)
            val before = output.readBytes()
            assertEquals(PhotoPreparationResult.PROCESSED, processor.prepare(output.path, ConversationMode.SOLVE))
            assertArrayEquals(before, output.readBytes())
            val result = requireNotNull(decodePhotoBitmap(QuestionPhotoFiles.displayPath(output.path)))
            try { assertTrue(result.width > 500 && result.height > 500) } finally { result.recycle() }
        } finally { settings.setQuestionPhotoPreprocessing(enabled) }
    }
}
