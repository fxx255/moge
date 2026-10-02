package com.moge.app.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.NotebookRepository
import com.moge.app.data.db.NotebookEntryEntity
import com.moge.app.data.db.MogeDatabase
import com.moge.app.data.db.RequestRepository
import com.moge.app.data.figure.DiagramImageStore
import com.moge.app.data.figure.PlotImageStore
import com.moge.app.domain.RequestStatus
import com.moge.app.domain.SolveMode
import com.moge.app.ui.capture.CaptureStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * 孤儿回收：真实 Room（内存）+ 真实 DraftStore + Robolectric filesDir。
 * 重点是「不该删的绝不删」：消息、封面、请求附件、草稿（含未落盘的）、宽限期内的新照片，
 * 以及任何引用扫描失败时整轮放弃。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AttachmentJanitorTest {

    private lateinit var context: Context
    private lateinit var db: MogeDatabase
    private lateinit var conversations: ConversationRepository
    private lateinit var requests: RequestRepository
    private lateinit var drafts: DraftStore
    private lateinit var plots: PlotImageStore
    private lateinit var diagrams: DiagramImageStore
    private lateinit var notebook: NotebookRepository
    private lateinit var janitor: AttachmentJanitor

    private val now = 10L * 24 * 60 * 60 * 1000
    private val old = now - AttachmentJanitor.PHOTO_GRACE_MS - 1
    private val key = "a".repeat(64)
    private val otherKey = "b".repeat(64)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        db = Room.inMemoryDatabaseBuilder(context, MogeDatabase::class.java).allowMainThreadQueries().build()
        conversations = ConversationRepository(db.conversationDao(), Dispatchers.Unconfined)
        requests = RequestRepository(db.requestDao(), Dispatchers.Unconfined)
        drafts = DraftStore(context)
        notebook = NotebookRepository(db, db.notebookDao(), Dispatchers.Unconfined)
        plots = PlotImageStore(context)
        diagrams = DiagramImageStore(context)
        janitor = AttachmentJanitor(context, conversations, drafts, notebook, plots, diagrams, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        db.close()
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun file(dir: String, name: String, modified: Long = old, bytes: Int = 10): File =
        File(File(context.filesDir, dir).apply { mkdirs() }, name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(modified)
        }

    private fun photo(name: String, modified: Long = old) = file(CaptureStore.PHOTOS_DIR, name, modified)

    private suspend fun ask(photos: List<String>): String {
        val conversation = conversations.createConversation("题", SolveMode.DETAILED)
        // 已完成的请求：PREPARING/RUNNING 会被删题事务当作在途而保留。
        requests.createRequest(
            conversation.id, "u-${conversation.id}", "a-${conversation.id}", "att", "题", photos, "",
            status = RequestStatus.COMPLETED,
        )
        return conversation.id
    }

    @Test
    fun `deletes only unreferenced old photos`() = runBlocking {
        val kept = photo("kept.jpg")
        val orphan = photo("orphan.jpg")
        ask(listOf(kept.absolutePath))

        assertEquals(10L, janitor.sweep(now = now))
        assertTrue(kept.exists())
        assertFalse(orphan.exists())
    }

    @Test
    fun `dry run counts without deleting`() = runBlocking {
        val orphan = photo("orphan.jpg")
        assertEquals(10L, janitor.sweep(now = now, dryRun = true))
        assertTrue(orphan.exists())
    }

    @Test
    fun `photos inside grace period survive`() = runBlocking {
        val fresh = photo("fresh.jpg", modified = now - 1000)
        assertEquals(0L, janitor.sweep(now = now))
        assertTrue(fresh.exists())
    }

    @Test
    fun `cover and draft references are kept`() = runBlocking {
        val cover = photo("cover.jpg")
        val drafted = file(DraftStore.ATTACHMENTS_DIR, "draft.jpg")
        val id = ask(emptyList())
        conversations.setCoverIfEmpty(id, cover.absolutePath)
        drafts.save(id, "草稿", listOf(drafted.absolutePath))

        assertEquals(0L, janitor.sweep(now = now))
        assertTrue(cover.exists())
        assertTrue(drafted.exists())
    }

    @Test
    fun `unpersisted draft edit protects its photo`() = runBlocking {
        val pending = photo("pending.jpg")
        drafts.reserveSave(null, "", listOf(pending.absolutePath))

        assertEquals(0L, janitor.sweep(now = now))
        assertTrue(pending.exists())
    }

    @Test
    fun `reference through a non canonical path is kept`() = runBlocking {
        val kept = photo("kept.jpg")
        val roundabout = File(context.filesDir, "${CaptureStore.PHOTOS_DIR}/../${CaptureStore.PHOTOS_DIR}/kept.jpg")
        ask(listOf(roundabout.path))

        assertEquals(0L, janitor.sweep(now = now))
        assertTrue(kept.exists())
    }

    @Test
    fun `deleted conversation frees its photo`() = runBlocking {
        val shared = photo("shared.jpg")
        val solo = photo("solo.jpg")
        val a = ask(listOf(shared.absolutePath, solo.absolutePath))
        ask(listOf(shared.absolutePath))
        conversations.deleteIdle(setOf(a))

        assertEquals(10L, janitor.sweep(now = now))
        assertTrue("另一道题还在用", shared.exists())
        assertFalse(solo.exists())
    }

    @Test
    fun `figure variants follow their key`() = runBlocking {
        val figureOld = now - AttachmentJanitor.FIGURE_GRACE_MS - 1
        val light = file("plots", "plot_light_$key.png", figureOld)
        val dark = file("plots", "plot_dark_$key.png", figureOld)
        val ledger = file("plots", "spec_$key.json", figureOld)
        val orphanPng = file("plots", "plot_light_$otherKey.png", figureOld)
        val orphanLedger = file("plots", "spec_$otherKey.json", figureOld)
        // 回答只存了日间变体的路径；夜间变体和台账必须一起保留。
        val conversation = conversations.createConversation("题", SolveMode.DETAILED)
        requests.createRequest(conversation.id, "u", "a", "att", "题", emptyList(), "")
        db.openHelper.writableDatabase.execSQL(
            "UPDATE message SET image_paths = ? WHERE id = 'a'",
            arrayOf(RequestRepository.encodePathList(listOf(light.absolutePath))),
        )

        assertEquals(20L, janitor.sweep(now = now))
        assertTrue(light.exists() && dark.exists() && ledger.exists())
        assertFalse(orphanPng.exists() || orphanLedger.exists())
    }

    @Test
    fun `corrupt path json aborts the whole sweep`() = runBlocking {
        val orphan = photo("orphan.jpg")
        ask(emptyList())
        db.openHelper.writableDatabase.execSQL("UPDATE message SET image_paths = '[broken' WHERE role = 'user'")

        assertNull(janitor.sweep(now = now))
        assertTrue(orphan.exists())
    }

    @Test
    fun `draft scan failure aborts the whole sweep`() = runBlocking {
        val orphan = photo("orphan.jpg")
        val failing = mockk<DraftStore>()
        coEvery { failing.referencedPhotoPaths() } throws IOException("disk")
        val janitor = AttachmentJanitor(context, conversations, failing, notebook, plots, diagrams, Dispatchers.Unconfined)

        assertNull(janitor.sweep(now = now))
        assertTrue(orphan.exists())
    }

    @Test
    fun `unreadable draft file aborts the whole sweep`() = runBlocking {
        val orphan = photo("orphan.jpg")
        // 草稿「文件」被打成目录：读不出来，不能当作没有引用。
        File(context.filesDir, "drafts/draft_broken").mkdirs()

        assertNull(janitor.sweep(now = now))
        assertTrue(orphan.exists())
    }
    @Test fun `standalone favorite keeps photos alive after history deletion`() = runBlocking {
        val retained = photo("favorite.jpg")
        notebook.save(NotebookEntryEntity(sourceConversationId = "removed", sourceQuestionId = "q",
            sourceAnswerId = "a", title = "收藏", questionText = "题", answerText = "解答",
            questionImagePaths = RequestRepository.encodePathList(listOf(retained.absolutePath))))
        janitor.sweep(now = now)
        assertTrue(retained.isFile)
        notebook.clear()
        janitor.sweep(now = now)
        assertFalse(retained.exists())
    }

}
