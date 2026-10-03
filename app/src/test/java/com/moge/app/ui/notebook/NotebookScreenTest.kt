package com.moge.app.ui.notebook

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.data.db.NotebookEntryEntity
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NotebookScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun entry(id: String, title: String) = NotebookEntryEntity(id = id, sourceConversationId = "source",
        sourceQuestionId = "q$id", sourceAnswerId = "a$id", title = title, questionText = "保存的问题", answerText = "保存的解答")
    private val state = mutableStateOf(NotebookUiState(loading = false,
        categories = listOf(NotebookCategoryEntity(id = "category", name = "待复习")),
        entries = listOf(entry("a", "收藏一"), entry("b", "收藏二"))))
    private var opened: String? = null
    private var sourceOpened: String? = null
    private var moved: String? = null
    private var deleted = 0
    private var created: String? = null
    private var categoryDeleted: String? = null
    private fun render(largeChalk: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeChalk) 1.8f else 1f)) {
                MogeTheme(if (largeChalk) Appearance.CHALK else Appearance.PAPER) {
                    NotebookContent(state.value, onBack = {}, onOpenConversation = { sourceOpened = it },
                        onQuery = { state.value = state.value.copy(query = it) },
                        onCategory = { id, unclassified -> state.value = state.value.copy(categoryId = id, uncategorizedOnly = unclassified) },
                        onToggleSelection = { id -> state.value = state.value.copy(selectedIds =
                            if (id in state.value.selectedIds) state.value.selectedIds - id else state.value.selectedIds + id) },
                        onClearSelection = { state.value = state.value.copy(selectedIds = emptySet()) },
                        onSelectAll = { state.value = state.value.copy(selectedIds = setOf("a", "b")) },
                        onOpenEntry = { opened = it }, onCloseEntry = {}, onMove = { moved = it }, onDelete = { deleted++ },
                        onCreateCategory = { created = it }, onRenameCategory = { _, _ -> }, onReorderCategory = { _, _ -> },
                        onDeleteCategory = { categoryDeleted = it }, onRetry = {}, onDismissMessage = {})
                }
            }
        }
    }
    @Test fun `tap opens snapshot and batch move picks a custom category`() {
        render()
        compose.onNodeWithText("收藏一").performClick()
        assertEquals("a", opened)
        assertNull(sourceOpened)
        compose.onNodeWithText("收藏一").performTouchInput { longClick() }
        compose.onNodeWithText("全选当前结果").performClick()
        compose.onNodeWithContentDescription("移动分类").performClick()
        compose.onAllNodesWithText("待复习").onLast().performClick()
        assertEquals("category", moved)
    }
    @Test fun `removing favorites requires confirmation that preserves history`() {
        render()
        compose.onNodeWithText("收藏一").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("移出题册").performClick()
        compose.onNodeWithText("保存的题目与解答快照将被删除，历史对话仍保留。").assertExists()
        assertEquals(0, deleted)
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("移出题册").performClick()
        compose.onNodeWithText("移出题册", useUnmergedTree = true).performClick()
        assertEquals(1, deleted)
    }
    @Test fun `category creation accepts user name and deletion explains uncategorizing`() {
        render()
        compose.onNodeWithContentDescription("管理分类").performClick()
        compose.onNodeWithText("新建分类").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("考前复习")
        compose.onNodeWithText("保存").performClick()
        assertEquals("考前复习", created)
        compose.onNodeWithContentDescription("删除分类 待复习").performClick()
        compose.onNodeWithText("该分类的题目与历史对话会移至未分类，内容不会删除。").assertExists()
        compose.onNodeWithText("删除分类", substring = false).performClick()
        assertEquals("category", categoryDeleted)
    }
    @Test fun `large chalk type keeps search and uncategorized filtering reachable`() {
        render(largeChalk = true)
        compose.onNode(hasSetTextAction()).performTextInput("速度")
        assertEquals("速度", state.value.query)
        compose.onNode(hasText("未分类", substring = false) and
            androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,
                androidx.compose.ui.semantics.Role.Checkbox)).performClick()
        assertTrue(state.value.uncategorizedOnly)
        compose.onNodeWithContentDescription("清除搜索").performClick()
        assertEquals("", state.value.query)
    }
    @Test fun `snapshot reader shows source link only while history exists`() {
        val detail = entry("a", "收藏详情")
        state.value = state.value.copy(openEntryId = detail.id, detailEntry = detail, sourceExists = false)
        render()
        compose.onNodeWithText("题目").assertExists()
        compose.onNodeWithText("回到原对话").assertDoesNotExist()
        compose.onNodeWithText("原对话已删除或暂不可用，保存的题目与解答仍可阅读").performScrollTo().assertIsDisplayed()
        state.value = state.value.copy(sourceExists = true)
        compose.onNodeWithText("回到原对话").performScrollTo().performClick()
        assertEquals("source", sourceOpened)
    }
}
