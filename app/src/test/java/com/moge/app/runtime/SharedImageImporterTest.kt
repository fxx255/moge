package com.moge.app.runtime

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.MogeDatabase
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.capture.CaptureStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [PosixAtomicFileShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedImageImporterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun image(name: String): File = File(context.cacheDir, name).also { file ->
        val bitmap = Bitmap.createBitmap(24, 36, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun importsOriginalsBeforeUriExpiresAndCreatesChosenModeWithoutOverwritingUnsentDraft() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MogeDatabase::class.java).allowMainThreadQueries().build()
        try {
            val drafts = DraftStore(context)
            drafts.persist(drafts.reserveSave(null, "原有未发送草稿", emptyList(), conversationMode = ConversationMode.SOLVE))
            val settings = mockk<SettingsRepository>()
            coEvery { settings.current() } returns UserSettings()
            val importer = SharedImageImporter(context, CaptureStore(context),
                ConversationRepository(db.conversationDao(), Dispatchers.Unconfined), drafts, settings)
            val first = image("shared-first.png")
            val second = image("shared-second.png")
            val raw = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(Uri.fromFile(first)))
                .putExtra(Intent.EXTRA_TEXT, "请看这两张图")
            raw.clipData = ClipData.newRawUri("图片", Uri.fromFile(first)).apply {
                addItem(ClipData.Item(Uri.fromFile(second)))
                addItem(ClipData.Item(Uri.parse("https://example.com/image.png")))
            }
            assertEquals(listOf(Uri.fromFile(first), Uri.fromFile(second)), importer.imageUris(raw))
            val share = importer.import(raw)!!
            val bytes = listOf(first.readBytes(), second.readBytes())
            first.delete(); second.delete()
            assertEquals(2, share.photoPaths.size)
            share.photoPaths.forEachIndexed { index, path -> assertArrayEquals(bytes[index], File(path).readBytes()) }
            for (mode in ConversationMode.entries) {
                val id = importer.createDraft(share, mode)
                val stored = DraftStore(context).load(id)!!
                assertEquals(share.photoPaths, stored.photoPaths)
                assertEquals("请看这两张图", stored.text)
                // Saved conversations resolve their immutable mode from Room, not a draft marker.
                assertEquals(mode.name, db.conversationDao().getConversation(id)!!.conversationMode)
                assertTrue(db.conversationDao().getMessages(id).isEmpty())
            }
            importer.discard(share)
            assertTrue("Dismissing a retried share must not delete images now owned by a draft",
                share.photoPaths.all { File(it).isFile })
            assertEquals("原有未发送草稿", drafts.load(null)!!.text)
        } finally { db.close() }
    }

    @Test fun invalidImageRollsBackPartialImportsAndLimitIsCheckedBeforeCopying() = runBlocking {
        val store = CaptureStore(context)
        val importer = SharedImageImporter(context, store, mockk(), mockk(), mockk())
        val first = image("valid-shared.png")
        val invalid = File(context.cacheDir, "fake-image.png").apply { writeText("not an image") }
        val folder = File(context.filesDir, CaptureStore.PHOTOS_DIR).apply { mkdirs() }
        val before = folder.listFiles().orEmpty().map { it.name }.toSet()
        val raw = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(Uri.fromFile(first), Uri.fromFile(invalid)))
        assertTrue(runCatching { importer.import(raw) }.isFailure)
        assertEquals(before, folder.listFiles().orEmpty().map { it.name }.toSet())
        val many = (0..CaptureStore.MAX_PHOTOS).map { Uri.parse("content://test/image/$it") }
        raw.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(many))
        assertTrue(runCatching { importer.import(raw) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before, folder.listFiles().orEmpty().map { it.name }.toSet())
        assertTrue(importer.imageUris(Intent(Intent.ACTION_SEND).setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://test/document"))).isEmpty())
    }
}
