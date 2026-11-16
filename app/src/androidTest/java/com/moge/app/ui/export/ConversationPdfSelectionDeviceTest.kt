package com.moge.app.ui.export

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moge.app.data.prefs.Appearance
import com.moge.app.domain.ConversationExportItem
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Edit real Android dialog fields/IME; the JVM host cannot drive this popup's frame scheduling. */
@RunWith(AndroidJUnit4::class)
class ConversationPdfSelectionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun dateRangeRejectsReversedDatesAndAllowsOpenEndpoint() {
        val state = mutableStateOf(ConversationPdfUiState(loading = false, items = listOf(
            ConversationExportItem("one", "较新记录", "", null, Instant.parse("2026-11-16T09:00:00Z")),
            ConversationExportItem("two", "较早记录", "", null, Instant.parse("2026-11-15T09:00:00Z")),
        )))
        compose.setContent { MogeTheme(Appearance.CHALK, uiScalePercent = 150, bodyTextSizeSp = 24) {
            Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                ConversationPdfContent(state.value, {}, { state.value = state.value.copy(filter = it) }, {}, {}, {}, {}, {}, {}, {})
            }
        } }
        compose.onNodeWithText("全部时间").performClick()
        compose.onNodeWithTag("pdf-date-from").performScrollTo().performTextReplacement("2026-11-16")
        closeSoftKeyboard()
        compose.onNodeWithTag("pdf-date-through").performScrollTo().performTextReplacement("2026-11-15")
        closeSoftKeyboard()
        compose.onNodeWithText("应用").assertIsNotEnabled()
        compose.onNodeWithTag("pdf-date-from").performScrollTo().performTextReplacement("")
        closeSoftKeyboard()
        compose.onNodeWithText("应用").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertNull(state.value.filter.from)
            assertEquals("2026-11-15", state.value.filter.through.toString())
        }
        compose.onNodeWithTag("pdf-selection-list").performScrollToNode(hasTestTag("pdf-item-two"))
        compose.onNodeWithTag("pdf-item-two").assertIsDisplayed()
        compose.onNodeWithTag("pdf-item-one").assertDoesNotExist()
    }
}
