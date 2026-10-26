package com.moge.app.ui.solve

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.components.PaperNavigationLayers
import com.moge.app.ui.components.PaperScaffold
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FavoritePresentationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val current = SolveItem.Question("follow", "这里的积分范围为什么要分成两段？", emptyList(), "", false)
    private val categories = listOf(NotebookCategoryEntity(id = "math", name = "高数"),
        NotebookCategoryEntity(id = "signals", name = "通信原理"), NotebookCategoryEntity(id = "english", name = "英语"))

    private fun snapshot(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try {
                File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun questionAndDestinationSelectionUseIndependentPaperInBothThemes() {
        val image = File(compose.activity.cacheDir, "favorite-question.png")
        val photo = Bitmap.createBitmap(900, 230, Bitmap.Config.ARGB_8888)
        try {
            photo.eraseColor(Color.WHITE)
            Canvas(photo).drawText("y = sin x,   0 < x < 2π", 45f, 130f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f })
            image.outputStream().use { photo.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { photo.recycle() }
        val original = SolveItem.Question("original", "求图中阴影部分绕坐标轴旋转一周的体积。", listOf(image.path), "", true)
        val theme = mutableStateOf(Appearance.PAPER)
        val choosing = mutableStateOf(true)
        var chosen: SolveItem.Question? = null
        var saved: Pair<String?, String>? = null
        try {
            compose.setContent { MogeTheme(theme.value) { PaperNavigationLayers {
                PaperScaffold("不应透出的底层标题", null) { androidx.compose.material3.Text("底层对话正文".repeat(80)) }
                if (choosing.value) QuestionSelectionDialog(current, listOf(original), true, {}, { chosen = it; choosing.value = false })
                else Dialog({}, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                    FavoriteDialogContent(FavoriteTarget("conversation", "旋转体的体积积分", requireNotNull(chosen),
                        SolveItem.Answer("answer", AnswerState.COMPLETED, "完整解答")), categories,
                        null, false, false, false, null, {}, { id, name -> saved = id to name })
                }
            } } }
            for (appearance in listOf(Appearance.PAPER, Appearance.CHALK)) {
                compose.runOnUiThread { theme.value = appearance; choosing.value = true }
                compose.onNodeWithTag("question-choice-original").performScrollTo().performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithContentDescription("待确认的题目照片 1").fetchSemanticsNodes().isNotEmpty()
                }
                snapshot("favorite-question-$appearance.png")
                compose.onNodeWithTag("question-selection-confirm").assertIsDisplayed().performClick()
                assertEquals(original.photoPaths, chosen!!.photoPaths)
                assertTrue(chosen!!.text.contains(current.text))
                compose.onNodeWithTag("favorite-categories").performScrollToNode(hasText("高数"))
                compose.onNodeWithText("高数").performClick()
                compose.onNodeWithTag("favorite-categories").performScrollToIndex(0)
                snapshot("favorite-destination-$appearance.png")
                compose.onNodeWithTag("favorite-save").assertIsDisplayed().performClick()
                assertEquals("math" to "", saved)
            }
        } finally { image.delete() }
    }

    @Test fun inlineCategoryCreationRemainsVisibleAboveKeyboard() {
        var saved: Pair<String?, String>? = null
        compose.setContent { MogeTheme {
            Dialog({}, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                FavoriteDialogContent(FavoriteTarget("conversation", "旋转体的体积积分", current,
                    SolveItem.Answer("answer", AnswerState.COMPLETED, "解答")), categories,
                    null, false, false, false, null, {}, { id, name -> saved = id to name })
            }
        } }
        compose.onNodeWithTag("favorite-add-category").performClick()
        compose.onNodeWithTag("category-name-editor").assertIsDisplayed().assertIsFocused().performTextInput("考前复习")
        compose.waitUntil(5_000) {
            var visible = false
            compose.runOnUiThread {
                visible = android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                    it.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
                }
            }
            visible
        }
        compose.onNodeWithTag("category-name-editor").assertIsDisplayed()
        compose.onNodeWithContentDescription("保存分类名称").assertIsDisplayed()
        snapshot("favorite-create-category.png")
        compose.onNodeWithTag("category-name-editor").performImeAction()
        compose.onNodeWithTag("favorite-save").assertIsDisplayed().performClick()
        assertEquals(null to "考前复习", saved)
    }
}
