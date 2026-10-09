package com.moge.app.ui.history

import androidx.lifecycle.SavedStateHandle
import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.HistoryEntry
import com.moge.app.data.db.NotebookRepository
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.runtime.DraftStore
import com.moge.app.runtime.GenerationManager
import com.moge.app.runtime.GenerationManager.ActiveState
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = mockk<ConversationRepository>()
    private val categories = MutableStateFlow(listOf(NotebookCategoryEntity(id = "custom", name = "我的分组")))
    private val notebooks = mockk<NotebookRepository> { every { observeCategories() } returns categories }
    private val drafts = mockk<DraftStore>(relaxed = true)
    private val active = MutableStateFlow(ActiveState())
    private val manager = mockk<GenerationManager> { every { this@mockk.active } returns this@HistoryViewModelTest.active }
    private fun entry(id: String) = HistoryEntry(ConversationEntity(id = id, title = id), "题目$id")
    private val entries = MutableStateFlow(listOf(entry("a"), entry("b")))
    private val prefs = MutableStateFlow(UserSettings())
    private val settings = mockk<SettingsRepository> { every { this@mockk.settings } returns prefs }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { repository.observeHistory(any(), any(), any()) } returns entries
    }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun vm(handle: SavedStateHandle = SavedStateHandle()) = HistoryViewModel(handle, repository, drafts, manager, notebooks, settings)

    @Test fun historyRetentionHintFollowsSettingsChanges() {
        val model = vm()
        prefs.value = UserSettings(historyRetentionDays = 30)
        assertEquals(30, model.state.value.retentionDays)
        prefs.value = prefs.value.copy(historyAutoCleanupEnabled = false)
        assertFalse(model.state.value.autoCleanupEnabled)
    }

    @Test fun openingSearchResultRevealsItsHiddenMessageBeforeNavigation() {
        val model = vm()
        model.setQuery("旧版本")
        coEvery { repository.revealSearchMatch("a", "旧版本") } returns "old-question"
        var opened: Pair<String, String?>? = null
        model.openConversation("a") { id, message -> opened = id to message }
        assertEquals("a" to "old-question", opened)
        assertFalse(model.state.value.busy)
        coVerify(exactly = 1) { repository.revealSearchMatch("a", "旧版本") }
    }

    @Test fun `search restores and clears hidden selections`() {
        val handle = SavedStateHandle(mapOf("historyQuery" to "函数"))
        val model = vm(handle)
        verify { repository.observeHistory("函数") }
        model.toggleSelection("a")
        model.setQuery("速度")
        assertTrue(model.state.value.selectedIds.isEmpty())
        assertEquals("速度", handle.get<String>("historyQuery"))
        dispatcher.scheduler.advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        verify { repository.observeHistory("速度") }
    }

    @Test fun `typing is debounced and keeps showing previous results`() {
        val model = vm()
        model.setQuery("函")
        model.setQuery("函数")
        assertEquals(2, model.state.value.entries.size)
        verify(exactly = 0) { repository.observeHistory("函") }
        verify(exactly = 0) { repository.observeHistory("函数") }
        dispatcher.scheduler.advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        verify(exactly = 1) { repository.observeHistory("函数") }
        verify(exactly = 0) { repository.observeHistory("函") }
        model.setQuery("")
        verify(exactly = 2) { repository.observeHistory("") }
    }

    @Test fun `selection restores prunes removed rows and select all covers current results`() {
        val model = vm(SavedStateHandle(mapOf("historySelected" to arrayListOf("a", "gone"))))
        assertEquals(setOf("a"), model.state.value.selectedIds)
        model.selectAll()
        assertEquals(setOf("a", "b"), model.state.value.selectedIds)
        entries.value = listOf(entry("b"))
        assertEquals(setOf("b"), model.state.value.selectedIds)
        model.clearSelection()
        assertFalse(model.state.value.selecting)
    }

    @Test fun `rename requires single selection and preserves selection after validation error`() {
        val model = vm()
        model.selectAll()
        model.renameSelected("新标题")
        coVerify(exactly = 0) { repository.rename(any(), any()) }
        model.toggleSelection("b")
        coEvery { repository.rename("a", " ") } throws IllegalArgumentException("标题不能为空")
        model.renameSelected(" ")
        assertEquals(setOf("a"), model.state.value.selectedIds)
        assertEquals("标题不能为空", model.state.value.message)
        coEvery { repository.rename("a", "新标题") } returns true
        model.renameSelected("新标题")
        assertEquals("标题已修改", model.state.value.message)
        assertFalse(model.state.value.selecting)
    }

    @Test fun `delete excludes active owner and clears drafts only for actually deleted rows`() {
        active.value = ActiveState(requestId = "r", conversationId = "b")
        val model = vm()
        model.selectAll()
        coEvery { repository.deleteIdle(setOf("a")) } returns setOf("a")
        model.deleteSelected()
        coVerify(exactly = 1) { drafts.clearConversation("a") }
        coVerify(exactly = 0) { drafts.clearConversation("b") }
        assertEquals(setOf("b"), model.state.value.selectedIds)
        assertTrue(model.state.value.message!!.contains("生成中的对话已保留"))
        assertFalse(model.state.value.busy)
    }

    @Test fun `database protection still applies when active flow has not updated`() {
        val model = vm()
        model.selectAll()
        coEvery { repository.deleteIdle(setOf("a", "b")) } returns setOf("a")
        model.deleteSelected()
        assertEquals(setOf("b"), model.state.value.selectedIds)
        coVerify(exactly = 0) { drafts.clearConversation("b") }
    }

    @Test fun `pin uses repository and clears selection`() {
        val model = vm()
        model.toggleSelection("a")
        coEvery { repository.setPinned("a", true) } just Runs
        model.pinSelected()
        coVerify { repository.setPinned("a", true) }
        assertFalse(model.state.value.selecting)
    }

    @Test fun `query failure exposes retry and retry subscribes again`() {
        every { repository.observeHistory(any()) } returns flow { throw IllegalStateException("disk") } andThen entries
        val model = vm()
        assertNotNull(model.state.value.error)
        model.retry()
        assertNull(model.state.value.error)
        assertEquals(2, model.state.value.entries.size)
    }

    @Test fun `history category filter persists and deleted group becomes uncategorized`() {
        val handle = SavedStateHandle()
        val model = vm(handle)
        model.toggleSelection("a")
        model.setCategory("custom", false)
        verify { repository.observeHistory("", "custom", false) }
        assertTrue(model.state.value.selectedIds.isEmpty())
        assertEquals("custom", handle.get<String>("historyCategory"))
        categories.value = emptyList()
        assertTrue(model.state.value.uncategorizedOnly)
        assertNull(model.state.value.categoryId)
        verify { repository.observeHistory("", null, true) }
    }
    @Test fun `drag move uses captured ids even before selected state arrives`() {
        coEvery { repository.moveToCategory(setOf("a"), "custom") } returns 1
        val model = vm()
        model.moveItems(setOf("a"), "custom")
        coVerify(exactly = 1) { repository.moveToCategory(setOf("a"), "custom") }
        assertTrue(model.state.value.message!!.contains("1"))
        assertFalse(model.state.value.busy)
    }
    @Test fun `drag deletion preserves active generation just like toolbar deletion`() {
        active.value = ActiveState(requestId = "running", conversationId = "a")
        coEvery { repository.deleteIdle(setOf("b")) } returns setOf("b")
        val model = vm()
        model.deleteItems(setOf("a", "b"))
        coVerify { repository.deleteIdle(setOf("b")) }
        assertTrue(model.state.value.message!!.contains("生成中的对话已保留"))
    }

    @Test fun `collecting history saves selected ids and exposes the actual single favorite id`() {
        val handle = SavedStateHandle()
        val model = vm(handle)
        model.toggleSelection("a")
        coEvery { notebooks.saveConversations(setOf("a")) } returns listOf("saved-a")
        model.collectSelected()
        coVerify(exactly = 1) { notebooks.saveConversations(setOf("a")) }
        assertEquals("saved-a", model.state.value.savedFavoriteId)
        assertTrue(model.state.value.message!!.contains("1"))
        assertFalse(model.state.value.busy)
        assertTrue(model.state.value.selectedIds.isEmpty())
        assertTrue(handle.get<ArrayList<String>>("historySelected")!!.isEmpty())
        model.dismissSavedFavorite()
        assertNull(model.state.value.savedFavoriteId)
    }

    @Test fun `collecting multiple entries reports count and no complete answer clears selection`() {
        val model = vm()
        model.selectAll()
        coEvery { notebooks.saveConversations(setOf("a", "b")) } returns listOf("saved-a", "saved-b")
        model.collectSelected()
        assertTrue(model.state.value.message!!.contains("2"))
        assertNull(model.state.value.savedFavoriteId)
        model.selectAll()
        coEvery { notebooks.saveConversations(setOf("a", "b")) } returns emptyList()
        model.collectSelected()
        assertTrue(model.state.value.message!!.contains("暂无完整解答"))
        assertTrue(model.state.value.selectedIds.isEmpty())
        assertNull(model.state.value.savedFavoriteId)
    }

    @Test fun `collect failure preserves selection and overlapping collections are ignored`() {
        val pending = CompletableDeferred<List<String>>()
        coEvery { notebooks.saveConversations(setOf("a")) } coAnswers { pending.await() }
        val model = vm()
        model.toggleSelection("a")
        model.collectSelected()
        assertTrue(model.state.value.busy)
        model.collectSelected()
        coVerify(exactly = 1) { notebooks.saveConversations(setOf("a")) }
        pending.completeExceptionally(IllegalStateException("disk"))
        assertFalse(model.state.value.busy)
        assertEquals(setOf("a"), model.state.value.selectedIds)
        assertEquals("disk", model.state.value.message)
        assertNull(model.state.value.savedFavoriteId)
    }
}
