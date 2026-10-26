package com.moge.app.ui.solve

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.components.LocalPaperNavigationScope
import com.moge.app.ui.components.PaperNavigationLayers
import com.moge.app.ui.components.PaperNavigationScope
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w480dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FavoriteFlowTest {
    @get:Rule val compose = createComposeRule()
    private val current = SolveItem.Question("follow", "为什么这里需要绝对值？", emptyList(), "", false)
    private val original = SolveItem.Question("original", "求旋转体的体积", listOf("missing.png"), "识别后的原题", true)
    private val target = FavoriteTarget("conversation", "旋转体体积积分", current,
        SolveItem.Answer("answer", AnswerState.COMPLETED, "完整解答"))
    private val categories = listOf(NotebookCategoryEntity(id = "math", name = "高数"),
        NotebookCategoryEntity(id = "signals", name = "通信原理"))

    @Test fun `late favorite loading does not override a category chosen by the user`() {
        var initial by mutableStateOf<String?>(null)
        var saved: Pair<String?, String>? = null
        compose.setContent { MogeTheme {
            FavoriteDialogContent(target, categories, initial, true, false, false, null, {},
                onSave = { id, name -> saved = id to name })
        } }
        compose.onNodeWithText("通信原理").performScrollTo().performClick()
        compose.runOnIdle { initial = "math" }
        compose.onNodeWithText("通信原理").assertIsSelected()
        compose.onNodeWithTag("favorite-save").performClick()
        assertEquals("signals" to "", saved)
    }

    @Test fun `inline category editor commits a valid name and saves the question with that destination`() {
        var saved: Pair<String?, String>? = null
        compose.setContent { MogeTheme(Appearance.CHALK) {
            FavoriteDialogContent(target, categories, null, false, false, false, null, {},
                onSave = { id, name -> saved = id to name })
        } }
        compose.onNodeWithTag("favorite-add-category").performClick()
        compose.onNodeWithTag("category-name-editor").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("favorite-save").assertIsNotEnabled()
        compose.onNodeWithContentDescription("保存分类名称").assertIsNotEnabled()
        compose.onNodeWithTag("category-name-editor").performTextInput("  考前复习  ")
        compose.onNodeWithTag("category-name-editor").performImeAction()
        compose.onNodeWithTag("favorite-new-category").assertExists()
        compose.onNodeWithContentDescription("长按拖动考前复习").assertDoesNotExist()
        compose.onNodeWithTag("favorite-save").performClick()
        assertEquals(null to "考前复习", saved)
        compose.onNodeWithText("高数").performScrollTo().performClick()
        compose.onNodeWithTag("favorite-save").performClick()
        assertEquals("math" to "", saved)
    }

    @Test fun `saving locks destinations and dismissal until the operation finishes`() {
        var busy by mutableStateOf(false)
        var saves = 0
        var dismissals = 0
        compose.setContent { MogeTheme {
            FavoriteDialogContent(target, categories, null, false, busy, false, "写入失败，请重试",
                { dismissals++ }, { _, _ -> saves++; busy = true })
        } }
        compose.onNodeWithTag("favorite-save").performClick()
        compose.onNodeWithTag("favorite-save").assertIsNotEnabled()
        compose.onNodeWithText("未分类").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, saves); assertEquals(0, dismissals); busy = false }
        compose.onNodeWithTag("favorite-save").assertIsEnabled()
    }

    @Test fun `favorite category workspace owns its background without changing navigation paper layers`() {
        lateinit var underlying: PaperNavigationScope
        compose.setContent { MogeTheme { PaperNavigationLayers {
            underlying = requireNotNull(LocalPaperNavigationScope.current)
            FavoriteDialogContent(target, categories, null, false, false, false, null, {}, { _, _ -> })
        } } }
        compose.runOnIdle {
            assertTrue(underlying.headers.isEmpty())
            assertTrue(underlying.controls.isEmpty())
        }
    }

    @Test fun `question choice waits for confirmation and preserves original image with follow up`() {
        var chosen: SolveItem.Question? = null
        compose.setContent { MogeTheme {
            QuestionSelectionDialog(current, listOf(original), true, {}, { chosen = it })
        } }
        compose.onNodeWithTag("question-choice-${current.id}").assertIsSelected()
        compose.onNodeWithTag("question-choice-${original.id}").performScrollTo().performClick()
        compose.onNodeWithTag("question-choice-${original.id}").assertIsSelected()
        assertNull(chosen)
        compose.onNodeWithTag("question-selection-confirm").performClick()
        assertEquals(original.photoPaths, chosen!!.photoPaths)
        assertEquals(original.transcript, chosen!!.transcript)
        assertTrue(chosen!!.text.contains(original.text))
        assertTrue(chosen!!.text.contains(current.text))
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `share question chooser and favorite controls remain reachable on a small chalk screen with large text`() {
        var choosing by mutableStateOf(true)
        var chosen: SolveItem.Question? = null
        var saved = false
        compose.setContent { MogeTheme(Appearance.CHALK) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(density.density, 1.5f)) {
            if (choosing) QuestionSelectionDialog(current, listOf(original), false, {}, { chosen = it; choosing = false })
            else FavoriteDialogContent(target, categories, null, false, false, false, null, {}, { _, _ -> saved = true })
        } } }
        compose.onNodeWithTag("question-selection-confirm").assertIsDisplayed().performClick()
        assertEquals(current, chosen)
        compose.onNodeWithText("通信原理").performScrollTo().performClick()
        compose.onNodeWithTag("favorite-save").assertIsDisplayed().performClick()
        assertTrue(saved)
    }
}
