package com.moge.app.ui.history

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.MainActivity
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.DatabaseModule
import com.moge.app.data.db.MessageEntity
import com.moge.app.domain.ConversationMode
import com.moge.app.domain.SolveMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real deletion and retained navigation entries must not replay transient feedback. */
@RunWith(AndroidJUnit4::class)
class HistoryFeedbackNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun deletingThenReadingOtherChatsDoesNotRepeatTheOldSnackbar() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = DatabaseModule.provideDatabase(context)
        val repository = ConversationRepository(database.conversationDao(), Dispatchers.IO)
        val prefix = "删除提示回归-${UUID.randomUUID().toString().take(8)}"
        val conversations = runBlocking {
            (1..3).map { index ->
                repository.createConversation("$prefix-$index", SolveMode.DETAILED, ConversationMode.CHAT).also {
                    database.requestDao().insertMessage(MessageEntity(
                        conversationId = it.id, role = "user", content = "删除提示导航回归内容"))
                }
            }
        }
        val message = "已删除 1 个对话"
        try {
            compose.onNodeWithContentDescription("历史对话").performClick()
            compose.onNodeWithContentDescription("搜索标题、消息或识别文本").performTextInput(prefix)
            pressBack()
            waitForCard(conversations[0].id)
            delete(conversations[0].id)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(message).assertIsDisplayed()
            repeat(3) { index ->
                val other = conversations[1 + index % 2]
                compose.onNodeWithTag("history-card-${other.id}").performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithContentDescription("新对话").fetchSemanticsNodes().isNotEmpty()
                }
                pressBack()
                waitForCard(other.id)
                compose.onNodeWithText(message).assertDoesNotExist()
            }
            // A second actual deletion must still produce its own identically worded result.
            delete(conversations[2].id)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(message).assertIsDisplayed()
        } finally {
            runBlocking { repository.deleteIdle(conversations.map { it.id }.toSet()) }
            database.close()
        }
    }

    private fun waitForCard(id: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("history-card-$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-card-$id").assertIsDisplayed()
    }

    private fun delete(id: String) {
        compose.onNodeWithTag("history-card-$id").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("删除所选对话").performClick()
        compose.onNodeWithText("删除", substring = false).performClick()
    }
}
