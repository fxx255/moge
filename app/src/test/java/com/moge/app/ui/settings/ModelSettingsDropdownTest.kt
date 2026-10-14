package com.moge.app.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moge.app.data.credential.*
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ModelSettingsDropdownTest {
    @get:Rule val compose = createComposeRule()
    private fun profile(id: String, vision: Boolean = false) = AiModelProfile(
        id, "模型 $id", "https://example.com/v1", "model-$id", vision,
        AiSearchProtocol.OFF, AiReasoningEffort.MEDIUM, true,
    )

    @Test fun `primary menu hides alternatives until clicked and keeps editing and deletion available`() {
        var state by mutableStateOf(ModelsUiState(profiles = listOf(profile("a"), profile("b")), activeId = "a"))
        var edited: String? = null
        var deleted: String? = null
        compose.setContent {
            MogeTheme(Appearance.PAPER) {
                ModelsSection(state, { state = state.copy(activeId = it) }, { edited = it }, {}, { deleted = it }, {})
            }
        }
        compose.onNodeWithTag("primary-model-selector-option-b").assertDoesNotExist()
        compose.onNodeWithTag("primary-model-selector").performClick()
        compose.onNodeWithTag("primary-model-selector-option-b").assertIsDisplayed().performClick()
        compose.onNodeWithTag("primary-model-selector-option-a").assertDoesNotExist()
        compose.onNodeWithContentDescription("编辑「模型 b」").performClick()
        compose.runOnIdle { assertEquals("b", state.activeId); assertEquals("b", edited) }
        compose.onNodeWithContentDescription("删除「模型 b」").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(null, deleted) }
    }

    @Test fun `vision menu only lists vision profiles and supports clearing a selection in chalk theme`() {
        var state by mutableStateOf(ModelsUiState(profiles = listOf(profile("text"), profile("vision", true)),
            activeId = "text", visionProfileId = "vision"))
        compose.setContent {
            MogeTheme(Appearance.CHALK) { Column { VisionSection(state) { state = state.copy(visionProfileId = it) } } }
        }
        compose.onNodeWithTag("vision-model-selector-option-vision").assertDoesNotExist()
        compose.onNodeWithTag("vision-model-selector").performClick()
        compose.onNodeWithTag("vision-model-selector-option-text").assertDoesNotExist()
        compose.onNodeWithTag("vision-model-selector-option-vision").assertIsDisplayed()
        compose.onNodeWithTag("vision-model-selector-option-none").performClick()
        compose.onNodeWithTag("vision-model-selector-option-vision").assertDoesNotExist()
        compose.runOnIdle { assertEquals(null, state.visionProfileId) }
    }

    @Test fun `empty primary selector and testing state prevent opening menus`() {
        var state by mutableStateOf(ModelsUiState())
        compose.setContent { MogeTheme { ModelsSection(state, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("primary-model-selector").assertIsNotEnabled()
        compose.runOnIdle { state = ModelsUiState(profiles = listOf(profile("a")), activeId = "a", testing = true) }
        compose.onNodeWithTag("primary-model-selector").assertIsNotEnabled()
    }
}
