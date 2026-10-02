package com.moge.app.ui.history

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.HistoryEntry
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
class HistoryScreenTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(HistoryUiState(loading = false, entries = listOf(
        HistoryEntry(ConversationEntity(id = "a", title = "数学题"), "求导"),
        HistoryEntry(ConversationEntity(id = "b", title = "物理题"), "求速度"),
    )))
    private var opened: String? = null
    private var renamed: String? = null
    private var deletes = 0

    private fun render(largeChalk: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeChalk) 1.8f else 1f)) {
                MogeTheme(if (largeChalk) Appearance.CHALK else Appearance.PAPER) {
                    HistoryContent(state.value, onBack = {}, onOpenConversation = { opened = it },
                        onQuery = { state.value = state.value.copy(query = it) },
                        onToggleSelection = { id -> state.value = state.value.copy(selectedIds =
                            if (id in state.value.selectedIds) state.value.selectedIds - id else state.value.selectedIds + id) },
                        onClearSelection = { state.value = state.value.copy(selectedIds = emptySet()) },
                        onSelectAll = { state.value = state.value.copy(selectedIds = setOf("a", "b")) },
                        onRename = { renamed = it }, onPin = {}, onDelete = { deletes++ }, onRetry = {}, onDismissMessage = {})
                }
            }
        }
    }

    @Test fun `tap opens existing question and long press enters selection with rename validation`() {
        render()
        compose.onNodeWithText("数学题").performClick()
        assertEquals("a", opened)
        compose.onNodeWithText("数学题").performTouchInput { longClick() }
        compose.onNodeWithText("已选 1 个").assertExists()
        compose.onNodeWithContentDescription("重命名").performClick()
        compose.onNode(hasSetTextAction() and hasText("对话标题")).performTextClearance()
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText("对话标题")).performTextInput("函数练习")
        compose.onNodeWithText("保存").performClick()
        assertEquals("函数练习", renamed)
    }

    @Test fun `multi selection disables rename and deleting requires dialog confirmation`() {
        render()
        compose.onNodeWithText("数学题").performTouchInput { longClick() }
        compose.onNodeWithText("全选当前结果").performClick()
        compose.onNodeWithText("已选 2 个").assertExists()
        compose.onNodeWithContentDescription("重命名").assertIsNotEnabled()
        compose.onNodeWithContentDescription("删除所选对话").performClick()
        assertEquals(0, deletes)
        compose.onNodeWithText("删除 2 个对话？").assertExists()
        compose.onNodeWithText("取消", substring = false).performClick()
        assertEquals(0, deletes)
        compose.onNodeWithContentDescription("删除所选对话").performClick()
        compose.onNodeWithText("删除", substring = false).performClick()
        assertEquals(1, deletes)
    }

    @Test fun `large chalk type keeps search and conversation actions reachable`() {
        render(largeChalk = true)
        compose.onNode(hasSetTextAction()).performTextInput("速度")
        assertEquals("速度", state.value.query)
        compose.onNodeWithContentDescription("清除搜索").performClick()
        assertEquals("", state.value.query)
        compose.onNodeWithText("数学题").assertIsDisplayed().performClick()
        assertEquals("a", opened)
    }

    @Test fun `reloading keeps previous cards instead of a spinner`() {
        state.value = state.value.copy(loading = true)
        render()
        compose.onNodeWithText("数学题").assertIsDisplayed()
        compose.onNodeWithContentDescription("正在读取历史").assertDoesNotExist()
        state.value = state.value.copy(entries = emptyList())
        compose.onNodeWithContentDescription("正在读取历史").assertExists()
    }
}
