package com.moge.app.ui.solve

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.pressBack
import android.os.SystemClock
import com.moge.app.MainActivity
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.DatabaseModule
import com.moge.app.data.db.MessageEntity
import com.moge.app.domain.ConversationMode
import com.moge.app.domain.SolveMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercise the real NavHost, Hilt ViewModels, database and Android back dispatcher together. */
@RunWith(AndroidJUnit4::class)
class ConversationBackDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun systemBackRestoresExistingChatAndNewPageStillSwitchesModes() = verify(deleted = false)
    @Test fun systemBackToDeletedChatRestoresSelectableNewPage() = verify(deleted = true)

    private fun verify(deleted: Boolean) {
        val database = DatabaseModule.provideDatabase(InstrumentationRegistry.getInstrumentation().targetContext)
        val repository = ConversationRepository(database.conversationDao(), Dispatchers.IO)
        val title = "返回回归-${UUID.randomUUID().toString().take(8)}"
        val conversation = runBlocking { repository.createConversation(title, SolveMode.DETAILED, ConversationMode.CHAT) }
        try {
            runBlocking { database.requestDao().insertMessage(MessageEntity(
                conversationId = conversation.id, role = "user", content = "返回导航回归内容",
            )) }
            compose.onNodeWithContentDescription("历史对话").performClick()
            compose.onNodeWithContentDescription("搜索标题、消息或识别文本").performTextInput(title)
            pressBack() // Dismiss the search keyboard before opening the matching card.
            try {
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("history-card-${conversation.id}").fetchSemanticsNodes().isNotEmpty() }
            } catch (error: Throwable) {
                compose.onRoot().printToLog("ConversationBackTree")
                throw error
            }
            compose.onNodeWithTag("history-card-${conversation.id}").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("新对话").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("新对话").performClick()
            compose.onNodeWithTag("conversation-mode-switch").assertIsEnabled()
            compose.onNodeWithTag("conversation-mode-icon", useUnmergedTree = true).performTouchInput { click() }
            compose.onNodeWithText("新题目").assertExists()
            compose.onNodeWithContentDescription("解题模式").assertExists()
            if (deleted) {
                runBlocking { assertEquals(setOf(conversation.id), repository.deleteIdle(setOf(conversation.id))) }
                // This second database connection models deletion while the original page
                // is absent. Let its WhileSubscribed cache stop before returning and requerying.
                SystemClock.sleep(5_500)
            }
            pressBack()
            compose.waitForIdle()
            if (deleted) {
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("conversation-mode-switch").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("新对话").assertExists()
                compose.onNodeWithContentDescription("对话模式").assertExists()
                compose.onNodeWithTag("conversation-mode-switch").assertIsEnabled()
                compose.onNodeWithTag("conversation-mode-icon", useUnmergedTree = true).performTouchInput { click() }
                compose.onNodeWithText("新题目").assertExists()
                compose.onNodeWithContentDescription("解题模式").assertExists()
                compose.onNodeWithContentDescription("历史对话").assertExists()
            } else {
                compose.onNodeWithText(title).assertExists()
                compose.onNodeWithContentDescription("对话模式").assertExists()
                compose.onNodeWithTag("conversation-mode-switch").assertDoesNotExist()
                compose.onNodeWithContentDescription("新对话").performClick()
                compose.onNodeWithTag("conversation-mode-switch").assertIsEnabled().performClick()
                compose.onNodeWithText("新题目").assertExists()
            }
        } finally {
            runBlocking { repository.deleteIdle(setOf(conversation.id)) }
            database.close()
        }
    }
}
