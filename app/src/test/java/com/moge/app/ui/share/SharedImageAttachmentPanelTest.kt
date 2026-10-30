package com.moge.app.ui.share

import android.app.Application
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.moge.app.data.prefs.Appearance
import com.moge.app.domain.ConversationMode
import com.moge.app.runtime.SharedImages
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w400dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedImageAttachmentPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chosenNewChatSurvivesRestorationAndCurrentConversationKeepsItsType() {
        val restore = StateRestorationTester(compose)
        var delivered: ConversationMode? = null
        var deliveries = 0
        restore.setContent { MogeTheme(appearance = Appearance.PAPER) {
            SharedImageAttachmentPanel(SharedImages("batch", listOf("/missing/one.png")),
                ConversationMode.SOLVE, "原来的题目", true, false, null,
                onConfirm = { delivered = it; deliveries++ }, onDismiss = {},
                modifier = Modifier.width(320.dp).heightIn(max = 480.dp))
        } }
        chooseDestination("新对话")
        compose.onNodeWithText("对话", substring = false).performScrollTo().performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("对话", substring = false).assertIsSelected()
        compose.onNodeWithText("添加到输入框").performClick()
        compose.runOnIdle { assertEquals(ConversationMode.CHAT, delivered); assertEquals(1, deliveries) }
        chooseDestination("当前对话")
        compose.onNodeWithText("当前类型：解题").assertExists()
        compose.onNodeWithText("对话", substring = false).assertDoesNotExist()
        compose.onNodeWithText("添加到输入框").performClick()
        compose.runOnIdle { assertNull(delivered); assertEquals(2, deliveries) }
    }

    @Test fun landscapeLargeTextKeepsTheConfirmActionReachableWhenContentNeedsToScroll() {
        var delivered: ConversationMode? = null
        compose.setContent { MogeTheme(appearance = Appearance.CHALK) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                SharedImageAttachmentPanel(SharedImages("large", List(9) { "/missing/$it.png" }),
                    null, null, false, false, "磁盘空间不足，请清理后重试",
                    onConfirm = { delivered = it }, onDismiss = {},
                    modifier = Modifier.width(320.dp).heightIn(max = 270.dp))
            }
        } }
        compose.onNodeWithTag("share-add-to-input").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("对话", substring = false).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("share-add-to-input").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ConversationMode.CHAT, delivered) }
    }

    private fun chooseDestination(label: String) {
        compose.onNodeWithTag("share-destination-picker").performScrollTo().performClick()
        compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performScrollTo().performClick()
    }
}
