package com.moge.app.data.document

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.MogeDatabase
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import com.moge.app.runtime.DraftStore
import com.moge.app.runtime.PosixAtomicFileShadow
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [PosixAtomicFileShadow::class])
class DocumentShareImporterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun sharesAndOpenWithCreateSeparatePersistentDraftsAndKeepExistingInput() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MogeDatabase::class.java).allowMainThreadQueries().build()
        try {
            val drafts = DraftStore(context)
            drafts.persist(drafts.reserveSave(null, "还未发送的原问题", emptyList()))
            val settings = mockk<SettingsRepository>()
            coEvery { settings.current() } returns UserSettings()
            val importer = DocumentShareImporter(context, DocumentStore(context), drafts, ConversationRepository(db.conversationDao(), Dispatchers.Unconfined), settings)
            val source = File(context.cacheDir, "微信文档.txt").apply { writeText("文档正文") }
            val shared = importer.import(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(source)))!!
            val opened = importer.import(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(source), "text/plain"))!!
            assertNotEquals(shared, opened)
            source.delete()
            for (id in listOf(shared, opened)) {
                val saved = DraftStore(context).load(id)!!
                assertEquals("", saved.text)
                assertEquals(1, saved.documentPaths.size)
                assertEquals("文档正文", File(saved.documentPaths.single()).readText())
                assertEquals("微信文档.txt", db.conversationDao().getConversation(id)!!.title)
            }
            assertEquals("还未发送的原问题", drafts.load(null)!!.text)
        } finally { db.close() }
    }

    @Test fun clipDataCompletesStreamsWithoutDuplicatingDocumentsOrAcceptingWebLinks() {
        val first = Uri.parse("content://weixin/first.pdf")
        val second = Uri.parse("content://weixin/second.pdf")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/pdf")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first))
        intent.clipData = ClipData.newRawUri("文档", first).apply {
            addItem(ClipData.Item(second)); addItem(ClipData.Item(Uri.parse("https://example.com/file.pdf")))
        }
        assertEquals(listOf(first, second), DocumentShareImporter.documentUris(intent))
        assertTrue(DocumentShareImporter.documentUris(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, first)).isEmpty())
    }

    @Test fun openWithAcceptsStreamExtraAndShareAcceptsDataUri() {
        val uri = Uri.parse("content://com.tencent.mm.external.fileprovider/document/42")
        assertEquals(listOf(uri), DocumentShareImporter.documentUris(
            Intent(Intent.ACTION_VIEW).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)))
        assertEquals(listOf(uri), DocumentShareImporter.documentUris(
            Intent(Intent.ACTION_VIEW).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri.toString())))
        assertEquals(listOf(uri), DocumentShareImporter.documentUris(
            Intent(Intent.ACTION_SEND).setDataAndType(uri, "application/pdf")))
        val duplicated = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
        duplicated.clipData = ClipData.newRawUri("document", uri)
        assertEquals(listOf(uri), DocumentShareImporter.documentUris(duplicated))
        assertTrue(DocumentShareImporter.documentUris(
            Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_STREAM, "https://example.com/a.pdf")).isEmpty())
    }
}
