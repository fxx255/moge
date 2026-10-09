package com.moge.app.runtime

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.MessageEntity
import com.moge.app.data.db.MogeDatabase
import com.moge.app.data.db.NotebookEntryEntity
import com.moge.app.data.db.NotebookRepository
import com.moge.app.data.db.RequestRepository
import com.moge.app.data.figure.DiagramImageStore
import com.moge.app.data.figure.PlotImageStore
import com.moge.app.ui.capture.CaptureStore
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HistoryRetentionMaintenanceTest {
    private lateinit var context: Context
    private lateinit var db: MogeDatabase
    private lateinit var drafts: DraftStore
    private lateinit var retention: HistoryRetention
    private val settings = mockk<SettingsRepository>()
    private var prefs = UserSettings()
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val old = now.minusSeconds(8L * 24 * 60 * 60)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        db = Room.inMemoryDatabaseBuilder(context, MogeDatabase::class.java).allowMainThreadQueries().build()
        val history = ConversationRepository(db.conversationDao(), Dispatchers.Unconfined)
        drafts = DraftStore(context)
        val notebook = NotebookRepository(db, db.notebookDao(), Dispatchers.Unconfined)
        val janitor = AttachmentJanitor(context, history, drafts, notebook, PlotImageStore(context),
            DiagramImageStore(context), Dispatchers.Unconfined)
        coEvery { settings.current() } answers { prefs }
        retention = HistoryRetention(history, drafts, janitor, settings)
    }
    @After fun teardown() {
        db.close()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun file(dir: String, name: String) = File(File(context.filesDir, dir).apply { mkdirs() }, name).apply {
        writeText("attachment")
        setLastModified(old.toEpochMilli())
    }
    private suspend fun conversation(id: String, at: Instant, paths: List<String> = emptyList()) {
        db.conversationDao().insertConversation(ConversationEntity(id = id, title = id, createdAt = at, updatedAt = at))
        db.requestDao().insertMessage(MessageEntity(id = "q$id", conversationId = id, role = "user", content = "题目",
            imagePaths = RequestRepository.encodePathList(paths), createdAt = at))
    }

    @Test fun `expiry removes conversation drafts and orphans while shared references survive`() = runBlocking {
        val orphan = file(CaptureStore.PHOTOS_DIR, "orphan.jpg")
        val shared = file(CaptureStore.PHOTOS_DIR, "shared.jpg")
        val notebookPhoto = file(CaptureStore.PHOTOS_DIR, "notebook.jpg")
        val document = file("documents", "question.txt")
        val draftOnly = file(DraftStore.ATTACHMENTS_DIR, "draft.jpg")
        conversation("expired", old, listOf(orphan.path, shared.path, notebookPhoto.path))
        conversation("recent", now, listOf(shared.path))
        db.notebookDao().insertEntry(NotebookEntryEntity(id = "saved", sourceConversationId = "previously-deleted",
            sourceQuestionId = "q", sourceAnswerId = "a", title = "收藏", questionText = "题目", answerText = "解答",
            questionImagePaths = RequestRepository.encodePathList(listOf(notebookPhoto.path))))
        drafts.save("expired", "未发送", listOf(orphan.path), listOf(document.path))
        drafts.save("expired__edit_qexpired", "修改", listOf(orphan.path))
        drafts.save("expired__branch_aexpired", "另一分支", listOf(orphan.path))
        drafts.save("recent", "继续提问", listOf(draftOnly.path), listOf(document.path))

        assertEquals(1, retention.sweep(now))
        assertNull(db.conversationDao().getConversation("expired"))
        listOf("expired", "expired__edit_qexpired", "expired__branch_aexpired").forEach { assertNull(drafts.load(it)) }
        assertNotNull(drafts.load("recent"))
        assertFalse(orphan.exists())
        listOf(shared, notebookPhoto, document, draftOnly).forEach { assertTrue("Shared file was removed: ${it.name}", it.exists()) }
        assertNotNull(db.notebookDao().getEntry("saved"))
        assertEquals(0, retention.sweep(now))
    }

    @Test fun `disabled cleanup leaves history drafts and files intact then new period takes effect`() = runBlocking {
        val photo = file(CaptureStore.PHOTOS_DIR, "old.jpg")
        conversation("old", old, listOf(photo.path))
        drafts.save("old", "草稿", listOf(photo.path))
        prefs = UserSettings(historyAutoCleanupEnabled = false)
        assertEquals(0, retention.sweep(now))
        assertNotNull(db.conversationDao().getConversation("old"))
        assertNotNull(drafts.load("old"))
        assertTrue(photo.exists())
        prefs = UserSettings(historyRetentionDays = 30)
        assertEquals(0, retention.sweep(now))
        prefs = UserSettings(historyRetentionDays = 3)
        assertEquals(1, retention.sweep(now))
        assertNull(drafts.load("old"))
        assertFalse(photo.exists())
    }

    @Test fun `unreadable policy never falls back to destructive default cleanup`() = runBlocking {
        val photo = file(CaptureStore.PHOTOS_DIR, "safe.jpg")
        conversation("old", old, listOf(photo.path))
        coEvery { settings.current() } throws IOException("settings unavailable")
        assertTrue(runCatching { retention.sweep(now) }.exceptionOrNull() is IOException)
        assertNotNull(db.conversationDao().getConversation("old"))
        assertTrue(photo.exists())
    }
}
