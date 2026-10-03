package com.moge.app.ui.solve

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.MogeDatabase
import com.moge.app.data.db.RequestEntity
import com.moge.app.data.db.RequestRepository
import com.moge.app.data.llm.RequestSnapshot
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import com.moge.app.domain.FailureKind
import com.moge.app.domain.RequestStatus
import com.moge.app.domain.SolveMode
import com.moge.app.runtime.DraftStore
import com.moge.app.runtime.GenerationManager
import com.moge.app.runtime.GenerationManager.ActiveState
import com.moge.app.runtime.PosixAtomicFileShadow
import com.moge.app.ui.Routes
import com.moge.app.ui.relayCaptureResults
import com.moge.app.ui.capture.CaptureBatch
import com.moge.app.ui.capture.CaptureStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
import java.util.UUID

/**
 * 解题页 VM：发送 / 新题建会话 / 草稿消费 / 停止 / 重发与回滚。
 *
 * 管理器用桩（生成本身由 GenerationManagerTest 等覆盖）；Room、草稿用真实实现，
 * 这样「列表来自 Room」「草稿真的落盘」才是被测的。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, shadows = [PosixAtomicFileShadow::class])
class SolveViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: MogeDatabase
    private lateinit var requests: RequestRepository
    private lateinit var conversations: ConversationRepository
    private lateinit var drafts: DraftStore
    private lateinit var manager: GenerationManager
    private val active = MutableStateFlow(ActiveState())
    private val settings = mockk<SettingsRepository> {
        coEvery { current() } returns UserSettings(defaultSolveMode = SolveMode.CHECK_WORK)
        every { this@mockk.settings } returns kotlinx.coroutines.flow.flowOf(UserSettings(defaultSolveMode = SolveMode.CHECK_WORK))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MogeDatabase::class.java).allowMainThreadQueries().build()
        requests = RequestRepository(db.requestDao(), Dispatchers.Unconfined)
        conversations = ConversationRepository(db.conversationDao(), Dispatchers.Unconfined)
        drafts = DraftStore(context)
        captureStore = CaptureStore(context)
        manager = mockk(relaxed = true) {
            every { this@mockk.active } returns this@SolveViewModelTest.active
            // 真实落盘：用户消息 + 回答占位 + 请求记录，Room 流随之推送。
            coEvery { submit(any(), any(), any(), any(), any()) } coAnswers {
                val submission = firstArg<GenerationManager.Submission>()
                requests.createRequest(
                    conversationId = submission.conversationId,
                    userMessageId = UUID.randomUUID().toString(),
                    answerMessageId = UUID.randomUUID().toString(),
                    attemptId = "att-1",
                    userText = submission.userText,
                    attachmentPaths = submission.attachmentPaths,
                    snapshotJson = "",
                )
            }
            coEvery { captureRetrySnapshot(any(), any(), any(), any()) } returns RequestSnapshot(model = "m")
        }
    }

    @After
    fun tearDown() {
        collectors.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private lateinit var captureStore: CaptureStore

    private fun vm(conversationId: String? = null, capture: CaptureBatch? = null): SolveViewModel {
        val args = buildMap<String, Any?> {
            if (conversationId != null) put(Routes.ARG_CONVERSATION_ID, conversationId)
            if (capture != null) put(Routes.ARG_CAPTURE, Routes.encodeCapture(capture))
        }
        val handle = SavedStateHandle(args)
        lastHandle = handle
        return SolveViewModel(handle, manager, requests, conversations, drafts, settings, captureStore, mockk(relaxed = true)).also { model ->
            // stateIn(WhileSubscribed) 需要订阅者才会合成状态：模拟页面一直在看。
            collectors.launch { model.uiState.collect { } }
        }
    }

    private val collectors = CoroutineScope(SupervisorJob() + dispatcher)

    private lateinit var lastHandle: SavedStateHandle

    /** 私有 photos 目录里的一张真实（非空）照片。 */
    private fun photo(name: String = "p"): String =
        captureStore.newCameraFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }.absolutePath

    /**
     * Room 的 Flow 在自己的查询线程上发射：轮询到条件成立（最多 5 秒），
     * 而不是假设写入后同一帧就能看到。
     */
    private fun SolveViewModel.awaitState(predicate: (SolveUiState) -> Boolean): SolveUiState = runBlocking {
        withTimeout(5_000) {
            while (!predicate(uiState.value)) delay(10)
        }
        uiState.value
    }

    /** 造一条已中断的请求（含消息），返回其记录。 */
    private suspend fun interruptedRequest(conversationId: String, partial: String = "半截"): RequestEntity {
        val record = requests.createRequest(conversationId, "u-$partial", "a-$partial", "att-old", "原题", emptyList(), "{}")
        requests.interrupt(record.requestId, "att-old", partial, FailureKind.NETWORK, "网络断了")
        return requests.get(record.requestId)!!
    }

    @Test
    fun `new question creates conversation then submits and clears input`() {
        val vm = vm()
        vm.onInputChange("  求 x^2 的导数  ")
        vm.send()
        val state = vm.awaitState { it.items.size == 2 }

        val id = state.conversationId!!
        val conversation = runBlocking { db.conversationDao().getConversation(id) }!!
        assertEquals("求 x^2 的导数", conversation.title)
        assertEquals(SolveMode.CHECK_WORK.name, conversation.solveMode)
        coVerify {
            manager.submit(match { it.conversationId == id && it.userText == "求 x^2 的导数" }, any(), any(), any(), any())
        }
        assertEquals("", state.input)
        assertNull("新题目槽的草稿必须被消费", runBlocking { drafts.load(null) })
        assertTrue(state.items[0] is SolveItem.Question && (state.items[0] as SolveItem.Question).isFirst)
    }

    @Test
    fun `rejected submit discards the empty conversation and keeps the input`() {
        coEvery { manager.submit(any(), any(), any(), any(), any()) } throws GenerationManager.AlreadyRunningException()
        val vm = vm()
        vm.onInputChange("题目")
        vm.send()
        val state = vm.awaitState { it.notice != null }

        assertNull("提交被拒时不能留下空会话", state.conversationId)
        assertTrue(runBlocking { db.conversationDao().observeConversations().first() }.isEmpty())
        assertEquals("题目", state.input)
        assertEquals("题目", runBlocking { drafts.load(null) }?.text)
    }

    @Test
    fun `send is refused while another conversation is generating`() {
        active.value = ActiveState(requestId = "r", attemptId = "a", conversationId = "other", phase = RequestStatus.RUNNING)
        val vm = vm()
        vm.onInputChange("题目")
        vm.send()
        val state = vm.awaitState { it.notice != null }

        assertTrue(state.busyElsewhere)
        assertFalse(state.canSend)
        coVerify(exactly = 0) { manager.submit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `follow up in existing conversation submits to it`() = runBlocking {
        val existing = conversations.createConversation("旧题", SolveMode.DETAILED)
        val vm = vm(existing.id)
        vm.onInputChange("为什么")
        vm.send()
        vm.awaitState { it.items.size == 2 }
        coVerify { manager.submit(match { it.conversationId == existing.id }, any(), any(), any(), any()) }
        assertNull(drafts.load(existing.id))
    }

    @Test
    fun `draft is restored for the conversation`() = runBlocking {
        val existing = conversations.createConversation("旧题", SolveMode.DETAILED)
        drafts.save(existing.id, "没写完的追问", emptyList())
        val vm = vm(existing.id)
        assertEquals("没写完的追问", vm.awaitState { it.input.isNotEmpty() }.input)
    }

    @Test
    fun `live answer text comes from active state of this conversation only`() = runBlocking {
        val existing = conversations.createConversation("题", SolveMode.DETAILED)
        val record = requests.createRequest(existing.id, "u1", "a1", "att", "题", emptyList(), "")
        val vm = vm(existing.id)
        active.value = ActiveState(
            requestId = record.requestId, attemptId = "att", conversationId = existing.id,
            phase = RequestStatus.RUNNING, answerMessageId = "a1", partialText = "正在写", answerStarted = true,
        )
        val streaming = vm.awaitState { s -> (s.items.lastOrNull() as? SolveItem.Answer)?.text == "正在写" }
        assertTrue(streaming.generating)
        assertEquals(AnswerState.STREAMING, (streaming.items.last() as SolveItem.Answer).state)

        // 另一道题的活动状态绝不串进这一页。
        active.value = active.value.copy(conversationId = "other", answerMessageId = "a1")
        val fenced = vm.awaitState { !it.generating }
        assertTrue(fenced.busyElsewhere)
        assertEquals("", (fenced.items.last() as SolveItem.Answer).text)
    }

    @Test
    fun `stop cancels the active request of this conversation with its attempt`() = runBlocking {
        val existing = conversations.createConversation("题", SolveMode.DETAILED)
        active.value = ActiveState(requestId = "r1", attemptId = "att-9", conversationId = existing.id, phase = RequestStatus.RUNNING)
        vm(existing.id).stop()
        coVerify { manager.cancel("r1", "att-9") }
    }

    @Test
    fun `stop ignores generation of another conversation`() = runBlocking {
        val existing = conversations.createConversation("题", SolveMode.DETAILED)
        active.value = ActiveState(requestId = "r1", attemptId = "att", conversationId = "other", phase = RequestStatus.RUNNING)
        vm(existing.id).stop()
        coVerify(exactly = 0) { manager.cancel(any(), any()) }
    }

    @Test
    fun `interrupted last answer offers retry and retry hands over a new attempt`() = runBlocking {
        val existing = conversations.createConversation("题", SolveMode.DETAILED)
        val record = interruptedRequest(existing.id)
        val vm = vm(existing.id)
        val failed = vm.awaitState { s -> (s.items.lastOrNull() as? SolveItem.Answer)?.state == AnswerState.FAILED }
        val answer = failed.items.last() as SolveItem.Answer
        assertEquals("半截", answer.text)
        assertEquals("网络断了", answer.failureMessage)
        assertEquals(record.requestId, answer.retryRequestId)

        // 管理器把新 attempt 登记成活动状态 ⇒ 不应回滚。
        coEvery { manager.submitRetry(record.requestId, any()) } coAnswers {
            active.value = ActiveState(requestId = record.requestId, attemptId = secondArg(), conversationId = existing.id, phase = RequestStatus.PREPARING)
        }
        vm.retry(record.requestId)
        vm.awaitState { !it.submitting }
        val after = requests.get(record.requestId)!!
        assertEquals(RequestStatus.PREPARING.name, after.status)
        coVerify { manager.submitRetry(record.requestId, after.attemptId) }
        assertTrue(after.attemptId != "att-old")
    }

    @Test
    fun `retry rejected by manager is rolled back to a retryable interruption`() = runBlocking {
        val existing = conversations.createConversation("题", SolveMode.DETAILED)
        val record = interruptedRequest(existing.id)
        coEvery { manager.submitRetry(any(), any()) } throws GenerationManager.AlreadyRunningException()
        val vm = vm(existing.id)
        vm.retry(record.requestId)
        val state = vm.awaitState { it.notice != null && !it.submitting }

        val after = requests.get(record.requestId)!!
        assertEquals("不能卡在 PREPARING", RequestStatus.INTERRUPTED.name, after.status)
        assertEquals("半截", after.partialText)
        assertTrue(state.notice!!.contains("另一道题"))
    }

    @Test
    fun `old interruption is history once a newer question exists`() = runBlocking {
        val existing = conversations.createConversation("题", SolveMode.DETAILED)
        val old = interruptedRequest(existing.id, partial = "旧")
        requests.createRequest(existing.id, "u-new", "a-new", "att-n", "新追问", emptyList(), "")
        val vm = vm(existing.id)
        val state = vm.awaitState { it.items.size == 4 }
        val oldAnswer = state.items.filterIsInstance<SolveItem.Answer>().first { it.id == "a-旧" }
        assertNull("旧中断不给重发入口", oldAnswer.retryRequestId)

        vm.retry(old.requestId)
        vm.awaitState { it.notice != null }
        coVerify(exactly = 0) { manager.submitRetry(any(), any()) }
    }

    @Test
    fun `cancelled answer shows stopped without retry`() = runBlocking {
        val existing = conversations.createConversation("题", SolveMode.DETAILED)
        val record = requests.createRequest(existing.id, "u1", "a1", "att", "题", emptyList(), "")
        requests.cancel(record.requestId, "att", "写到一半")
        val answer = vm(existing.id)
            .awaitState { s -> (s.items.lastOrNull() as? SolveItem.Answer)?.state == AnswerState.STOPPED }
            .items.last() as SolveItem.Answer
        assertEquals("写到一半", answer.text)
        assertNull(answer.retryRequestId)
    }

    @Test
    fun `completed last answer regenerates in place with a new attempt`() = runBlocking {
        val existing = conversations.createConversation("题", SolveMode.DETAILED)
        val record = requests.createRequest(existing.id, "u1", "a1", "att-old", "题", emptyList(), "")
        requests.complete(record.requestId, "att-old", "a1", "旧答案")
        val vm = vm(existing.id)
        val done = vm.awaitState { s -> (s.items.lastOrNull() as? SolveItem.Answer)?.regenerateRequestId != null }
        assertEquals(record.requestId, (done.items.last() as SolveItem.Answer).regenerateRequestId)

        coEvery { manager.submitRetry(record.requestId, any()) } coAnswers {
            active.value = ActiveState(requestId = record.requestId, attemptId = secondArg(), conversationId = existing.id, phase = RequestStatus.PREPARING)
        }
        vm.regenerate(record.requestId)
        vm.awaitState { !it.submitting }
        val after = requests.get(record.requestId)!!
        assertEquals(RequestStatus.PREPARING.name, after.status)
        assertTrue(after.attemptId != "att-old")
        assertEquals("回答位置复用", "a1", after.answerMessageId)
        coVerify { manager.submitRetry(record.requestId, after.attemptId) }
    }

    @Test
    fun `regenerate rejected by manager rolls back to retryable interruption`() = runBlocking {
        val existing = conversations.createConversation("题", SolveMode.DETAILED)
        val record = requests.createRequest(existing.id, "u1", "a1", "att-old", "题", emptyList(), "")
        requests.cancel(record.requestId, "att-old", "停在这")
        coEvery { manager.submitRetry(any(), any()) } throws GenerationManager.AlreadyRunningException()
        val vm = vm(existing.id)
        vm.regenerate(record.requestId)
        vm.awaitState { it.notice != null && !it.submitting }
        val after = requests.get(record.requestId)!!
        assertEquals(RequestStatus.INTERRUPTED.name, after.status)
        assertEquals("旧正文还在", "停在这", after.partialText)
    }

    @Test
    fun `regenerate refuses an older answer once a newer question exists`() = runBlocking {
        val existing = conversations.createConversation("题", SolveMode.DETAILED)
        val old = requests.createRequest(existing.id, "u1", "a1", "att-old", "题", emptyList(), "")
        requests.complete(old.requestId, "att-old", "a1", "旧答案")
        requests.createRequest(existing.id, "u2", "a2", "att-n", "新追问", emptyList(), "")
        val vm = vm(existing.id)
        vm.awaitState { it.items.size == 4 }
        vm.regenerate(old.requestId)
        vm.awaitState { it.notice != null }
        coVerify(exactly = 0) { manager.submitRetry(any(), any()) }
        assertEquals(RequestStatus.COMPLETED.name, requests.get(old.requestId)!!.status)
    }

    @Test fun `resume persists the saved prefix and reuses the question and answer positions`() = runBlocking {
        val conversation = conversations.createConversation("题", SolveMode.DETAILED)
        val record = interruptedRequest(conversation.id, partial = "已经保存的推导")
        val vm = vm(conversation.id)
        vm.awaitState { it.items.size == 2 }
        coEvery { manager.submitRetry(record.requestId, any()) } coAnswers {
            active.value = ActiveState(requestId = record.requestId, attemptId = secondArg(),
                conversationId = conversation.id, phase = RequestStatus.PREPARING)
        }
        vm.resume(record.requestId)
        vm.awaitState { !it.submitting }
        val after = requests.get(record.requestId)!!
        assertEquals("已经保存的推导", com.moge.app.data.llm.SnapshotCodec.decode(after.snapshotJson)!!.continuationText)
        assertEquals(record.answerMessageId, after.answerMessageId)
        assertEquals(record.userMessageId, after.userMessageId)
        assertEquals(2, conversations.messages(conversation.id).size)
        coVerify(exactly = 1) { manager.submitRetry(record.requestId, after.attemptId) }
    }

    @Test fun `resume with no saved text offers resend without starting a new attempt`() = runBlocking {
        val conversation = conversations.createConversation("题", SolveMode.DETAILED)
        val record = interruptedRequest(conversation.id, partial = "")
        val vm = vm(conversation.id)
        val state = vm.awaitState { it.items.size == 2 }
        assertNull((state.items.last() as SolveItem.Answer).resumeRequestId)
        vm.resume(record.requestId)
        vm.awaitState { it.notice != null && !it.submitting }
        assertEquals(record.attemptId, requests.get(record.requestId)!!.attemptId)
        coVerify(exactly = 0) { manager.submitRetry(any(), any()) }
    }

    @Test fun `resume rejected at handoff retains the prefix as a recoverable interruption`() = runBlocking {
        val conversation = conversations.createConversation("题", SolveMode.DETAILED)
        val record = interruptedRequest(conversation.id, partial = "保留推导")
        val vm = vm(conversation.id)
        vm.awaitState { it.items.size == 2 }
        coEvery { manager.submitRetry(any(), any()) } throws GenerationManager.AlreadyRunningException()
        vm.resume(record.requestId)
        vm.awaitState { it.notice != null && !it.submitting }
        val after = requests.get(record.requestId)!!
        assertEquals(RequestStatus.INTERRUPTED.name, after.status)
        assertEquals("保留推导", after.partialText)
    }

    // ---------------- P6：拍题首问与追问配图 ----------------

    @Test
    fun `captured photos wait in original composer and send only after confirmation`() {
        val a = photo()
        val b = photo()
        val vm = vm(capture = CaptureBatch(listOf(a, b), SolveMode.DETAILED, note = "补充说明"))
        val pending = vm.awaitState { it.photos.size == 2 }
        assertNull(pending.conversationId)
        assertEquals("补充说明", pending.input)
        coVerify(exactly = 0) { manager.submit(any(), any(), any(), any(), any()) }
        assertNull(lastHandle.get<String>(Routes.ARG_CAPTURE))
        vm.send()
        val sent = vm.awaitState { it.items.size == 2 && !it.submitting }
        val conversation = runBlocking { db.conversationDao().getConversation(sent.conversationId!!) }!!
        assertEquals(SolveMode.CHECK_WORK.name, conversation.solveMode)
        assertEquals(File(a).canonicalPath, conversation.coverImage)
        coVerify(exactly = 1) { manager.submit(match {
            it.solveMode == SolveMode.CHECK_WORK && it.attachmentPaths == listOf(File(a).canonicalPath, File(b).canonicalPath)
        }, any(), any(), any(), any()) }
    }

    @Test
    fun `return from camera appends photos without replacing conversation or existing draft`() = runBlocking {
        val existing = conversations.createConversation("已有对话", SolveMode.DETAILED)
        drafts.save(existing.id, "已有文字", emptyList())
        val vm = vm(existing.id)
        vm.awaitState { it.input == "已有文字" }
        val path = photo()
        val navigationHandle = SavedStateHandle()
        collectors.launch { relayCaptureResults(navigationHandle, vm::onCaptureResult) }
        navigationHandle[Routes.CAPTURE_RESULT] = Routes.encodeCapture(CaptureBatch(listOf(path), SolveMode.DETAILED, "拍照备注"))
        val state = vm.awaitState { it.photos == listOf(path) }
        assertEquals(existing.id, state.conversationId)
        assertEquals("已有文字\n拍照备注", state.input)
        assertTrue(state.items.isEmpty())
        assertNull(navigationHandle.get<String>(Routes.CAPTURE_RESULT))
        assertNull(lastHandle.get<String>(Routes.ARG_CAPTURE))
        val second = photo()
        navigationHandle[Routes.CAPTURE_RESULT] = Routes.encodeCapture(CaptureBatch(listOf(second), SolveMode.DETAILED, ""))
        assertEquals(existing.id, vm.awaitState { it.photos == listOf(path, second) }.conversationId)
        coVerify(exactly = 0) { manager.submit(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sending a retained draft after source history deletion creates a new conversation`() = runBlocking {
        val old = conversations.createConversation("已删除对话", SolveMode.DETAILED)
        drafts.save(old.id, "继续提问", emptyList())
        val vm = vm(old.id)
        vm.awaitState { it.input == "继续提问" }
        conversations.deleteIdle(setOf(old.id))
        vm.send()
        val state = vm.awaitState { it.items.size == 2 && !it.submitting }
        assertTrue(state.conversationId != old.id)
        assertEquals("继续提问", (state.items.first() as SolveItem.Question).text)
        assertNull(conversations.getConversation(old.id))
        assertEquals("", state.input)
    }

    @Test
    fun `rejected capture keeps photos and note in the input for a manual resend`() {
        coEvery { manager.submit(any(), any(), any(), any(), any()) } throws GenerationManager.AlreadyRunningException()
        val a = photo()
        val vm = vm(capture = CaptureBatch(listOf(a), SolveMode.DETAILED, note = "只做第 2 问"))
        vm.awaitState { it.photos.isNotEmpty() }
        vm.send()
        val state = vm.awaitState { it.notice != null && !it.submitting }

        assertNull(state.conversationId)
        assertEquals(listOf(a), state.photos)
        assertEquals("只做第 2 问", state.input)
        assertTrue("照片在就能直接再点发送", state.canSend || state.busyElsewhere)
    }

    @Test
    fun `missing photo blocks the send before any conversation is created`() {
        val vm = vm()
        vm.addPhotos(listOf("/data/nowhere/gone.jpg"))
        vm.send()
        val state = vm.awaitState { it.notice != null && !it.submitting }

        assertNull(state.conversationId)
        assertTrue(runBlocking { db.conversationDao().observeConversations().first() }.isEmpty())
        coVerify(exactly = 0) { manager.submit(any(), any(), any(), any(), any()) }
        assertEquals("照片保留，让用户自己删", 1, state.photos.size)
    }

    @Test
    fun `follow up photos are sent with the text and saved in the draft meanwhile`() = runBlocking {
        val existing = conversations.createConversation("旧题", SolveMode.DETAILED)
        val vm = vm(existing.id)
        val p = photo()
        vm.addPhotos(listOf(p))
        vm.onInputChange("这张图第 3 步对吗")
        assertEquals("待发照片随草稿落盘", listOf(p), drafts.load(existing.id)?.photoPaths)

        vm.send()
        vm.awaitState { it.items.size == 2 && !it.submitting }
        coVerify {
            manager.submit(
                match { it.userText == "这张图第 3 步对吗" && it.attachmentPaths == listOf(File(p).canonicalPath) },
                any(), any(), any(), any(),
            )
        }
        assertTrue(vm.uiState.value.photos.isEmpty())
        assertNull(drafts.load(existing.id))
    }

    @Test
    fun `photo count is capped at nine`() {
        val vm = vm()
        vm.addPhotos(List(CaptureStore.MAX_PHOTOS + 2) { "/p/$it.jpg" })
        val state = vm.awaitState { it.photos.isNotEmpty() }
        assertEquals(CaptureStore.MAX_PHOTOS, state.photos.size)
        assertFalse(state.canAddPhoto)
        assertTrue(state.notice!!.contains("最多"))
    }

    @Test
    fun `capture route arg round trips and rejects junk`() {
        val batch = CaptureBatch(listOf("/a.jpg", "/b c.jpg"), SolveMode.CHECK_WORK, "备注 & ?=")
        assertEquals(batch, Routes.decodeCapture(Routes.encodeCapture(batch)))
        assertNull(Routes.decodeCapture("not json"))
        assertNull("没有照片不算拍题", Routes.decodeCapture(Routes.encodeCapture(batch.copy(photoPaths = emptyList()))))
    }
}
