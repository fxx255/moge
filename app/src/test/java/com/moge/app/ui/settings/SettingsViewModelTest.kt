package com.moge.app.ui.settings

import com.moge.app.data.credential.AiCredentialStore
import com.moge.app.data.credential.AiModelProfile
import com.moge.app.data.credential.AiReasoningEffort
import com.moge.app.data.credential.AiSearchProtocol
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.NotebookRepository
import com.moge.app.data.figure.DiagramImageStore
import com.moge.app.data.figure.PlotImageStore
import com.moge.app.data.llm.ModelClient
import com.moge.app.data.llm.ModelException
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import com.moge.app.runtime.AttachmentJanitor
import com.moge.app.runtime.DraftStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val credentials = mockk<AiCredentialStore>(relaxed = true)
    private val client = mockk<ModelClient>()
    private val plots = mockk<PlotImageStore>()
    private val diagrams = mockk<DiagramImageStore>()
    private val janitor = mockk<AttachmentJanitor>()
    private val conversations = mockk<ConversationRepository>()
    private val drafts = mockk<DraftStore>(relaxed = true)
    private val notebook = mockk<NotebookRepository>(relaxed = true)

    /** 内存版档案表，模拟 AiCredentialStore 的增删改。 */
    private val stored = mutableListOf<AiModelProfile>()
    private var activeId: String? = null

    private fun profile(id: String, name: String = id, vision: Boolean = false) = AiModelProfile(
        id, name, "https://api.example.com", "m-$id", vision, AiSearchProtocol.RESPONSES, AiReasoningEffort.LOW, true,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { settings.settings } returns flowOf(UserSettings())
        every { credentials.profiles() } answers { stored.toList() }
        every { credentials.activeProfileId() } answers { activeId ?: stored.firstOrNull()?.id }
        every { credentials.questionVisionProfileId() } returns null
        every { plots.pngBytes() } returns 1000L
        every { diagrams.pngBytes() } returns 24L
        coEvery { janitor.sweep(any(), any(), any(), true) } returns 4096L
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SettingsViewModel(
        settings, credentials, client, plots, diagrams, janitor, conversations, drafts, notebook, dispatcher,
    )

    private fun draft(apiKey: String = "sk-1") = ModelProfileDraft(
        "DeepSeek", "https://api.deepseek.com", "deepseek-chat", apiKey,
        false, AiSearchProtocol.RESPONSES, AiReasoningEffort.MEDIUM,
    )

    @Test
    fun loadsProfilesAndCacheSize() {
        stored += profile("a")
        val vm = vm()
        assertEquals(listOf("a"), vm.models.value.profiles.map { it.id })
        assertEquals("a", vm.models.value.activeId)
        assertEquals(1024L, vm.figureCacheBytes.value)
    }

    @Test
    fun saveValidatesLocallyWithoutTouchingStore() {
        val vm = vm()
        var closed = false
        vm.saveProfile(null, draft(apiKey = ""), onSaved = { closed = true })
        assertEquals("请填写 API 密钥", vm.editor.value.error)
        assertFalse(closed)
        verify(exactly = 0) { credentials.upsertProfile(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun saveThenTestsTheSavedProfile() {
        every { credentials.upsertProfile(null, any(), any(), any(), "sk-1", any(), any(), AiReasoningEffort.MEDIUM) } answers {
            profile("new", name = "DeepSeek").also { stored += it; activeId = it.id }
        }
        coEvery { client.testConnection("new") } returns "连接成功"
        val vm = vm()
        var closed = false
        vm.saveProfile(null, draft(), onSaved = { closed = true })
        assertTrue(closed)
        assertEquals("连接成功", vm.models.value.message)
        assertFalse(vm.models.value.testing)
        assertEquals("new", vm.models.value.activeId)
    }

    @Test
    fun editingWithBlankKeyKeepsStoredKey() {
        stored += profile("a")
        every { credentials.upsertProfile("a", any(), any(), any(), "", any(), any(), any()) } returns profile("a")
        coEvery { client.testConnection("a") } returns "ok"
        val vm = vm()
        vm.saveProfile("a", draft(apiKey = ""), onSaved = {})
        assertEquals(null, vm.editor.value.error)
        verify { credentials.upsertProfile("a", any(), any(), any(), "", any(), any(), any()) }
    }

    @Test
    fun connectionFailureIsReportedNotThrown() {
        stored += profile("a")
        coEvery { client.testConnection(null) } throws ModelException(ModelException.Kind.UNAUTHORIZED, "API 密钥被拒绝")
        val vm = vm()
        vm.testActiveProfile()
        assertEquals("API 密钥被拒绝", vm.models.value.message)
        assertFalse(vm.models.value.testing)
    }

    @Test
    fun fetchModelsKeepsOldListOnFailure() {
        coEvery { client.fetchModels("https://api.deepseek.com", "sk", null) } returns listOf("a", "b")
        val vm = vm()
        vm.fetchModels("https://api.deepseek.com", "sk", null)
        assertEquals(listOf("a", "b"), vm.editor.value.models)

        coEvery { client.fetchModels(any(), any(), any()) } throws IllegalStateException("boom")
        vm.fetchModels("https://api.deepseek.com", "sk", null)
        assertEquals(listOf("a", "b"), vm.editor.value.models)
        assertTrue(vm.editor.value.fetchMessage!!.contains("boom"))
        assertFalse(vm.editor.value.modelsBusy)

        vm.invalidateModels()
        assertTrue(vm.editor.value.models.isEmpty())
    }

    @Test
    fun fetchModelsRejectsBadUrlBeforeNetwork() {
        val vm = vm()
        vm.fetchModels("deepseek.com", "sk", null)
        assertTrue(vm.editor.value.fetchMessage!!.contains("http"))
        coVerify(exactly = 0) { client.fetchModels(any(), any(), any()) }
    }

    @Test
    fun deleteClearsDanglingVisionSelection() {
        stored += profile("a")
        stored += profile("eye", vision = true)
        every { credentials.deleteProfile("eye") } answers { stored.removeAll { it.id == "eye" }; stored.first() }
        val vm = vm()
        vm.deleteProfile("eye")
        verify { credentials.setQuestionVisionProfileId(null) }
        assertEquals("已删除「eye」", vm.models.value.message)
        assertEquals(listOf("a"), vm.models.value.profiles.map { it.id })
    }

    @Test
    fun clearFigureCacheReportsFreedBytes() {
        every { plots.clearPngs() } returns 2048L
        every { diagrams.clearPngs() } returns 0L
        val vm = vm()
        every { plots.pngBytes() } returns 0L
        every { diagrams.pngBytes() } returns 0L
        vm.clearFigureCache()
        assertEquals(0L, vm.figureCacheBytes.value)
        assertTrue(vm.cacheMessage.value!!.startsWith("已清理 2.0 KB"))
    }

    @Test
    fun orphanEstimateIsDryRun() {
        val vm = vm()
        assertEquals(4096L, vm.orphanBytes.value)
        coVerify(exactly = 0) { janitor.sweep(any(), any(), any(), false) }
    }

    @Test
    fun clearOrphansSweepsForRealAndRefreshes() {
        coEvery { janitor.sweep(any(), any(), any(), false) } returns 4096L
        val vm = vm()
        coEvery { janitor.sweep(any(), any(), any(), true) } returns 0L
        vm.clearOrphans()
        assertEquals("已释放 4.0 KB", vm.cacheMessage.value)
        assertEquals(0L, vm.orphanBytes.value)
        assertFalse(vm.dataBusy.value)
    }

    @Test
    fun clearOrphansReportsAbortedScan() {
        coEvery { janitor.sweep(any(), any(), any(), false) } returns null
        val vm = vm()
        vm.clearOrphans()
        assertEquals("无法确认哪些文件仍在使用，本次没有删除任何文件", vm.cacheMessage.value)
    }

    @Test
    fun clearNotebookPreservesHistoryDraftsAndCategories() {
        val vm = vm()
        vm.clearNotebook()
        coVerify(exactly = 1) { notebook.clear() }
        coVerify(exactly = 0) { conversations.deleteAllIdle() }
        coVerify(exactly = 0) { drafts.clear(any()) }
        assertEquals("已清空题册收藏，历史对话与分类已保留", vm.cacheMessage.value)
    }
}
