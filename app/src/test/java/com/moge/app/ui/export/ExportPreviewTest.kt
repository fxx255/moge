package com.moge.app.ui.export

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.material3.MaterialTheme
import com.moge.app.data.prefs.Appearance
import androidx.compose.ui.test.performClick
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportPreviewTest {
    @get:Rule val compose = createComposeRule()

    private fun image(color: Int) = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun awaitImage(description: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun `switching pages keeps displayed bitmaps alive and never reuses a stale preview`() {
        val first = image(Color.RED)
        val third = image(Color.BLUE)
        val pendingSecond = CompletableDeferred<Bitmap?>()
        var path by mutableStateOf("first")
        val decoder: suspend (String, Int) -> Bitmap? = { source, _ ->
            when (source) { "first" -> first; "second" -> pendingSecond.await(); else -> third }
        }
        compose.setContent {
            MogeTheme { ExportPreviewImage(path, "Preview $path", previewPage = true, decoder = decoder) }
        }
        awaitImage("Preview first")
        compose.runOnIdle { path = "second" }
        assertFalse("A bitmap handed to Compose must survive in-flight drawing after page disposal", first.isRecycled)
        compose.onNodeWithContentDescription("Preview second").assertDoesNotExist()
        val retainedFrame = image(Color.WHITE)
        try { Canvas(retainedFrame).drawBitmap(first, 0f, 0f, Paint()) }
        finally { retainedFrame.recycle() }
        compose.runOnIdle { path = "third" }
        awaitImage("Preview third")
        pendingSecond.complete(image(Color.GREEN))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Preview third").assertExists()
        compose.onNodeWithContentDescription("Preview second").assertDoesNotExist()
        assertFalse(first.isRecycled)
        assertFalse(third.isRecycled)
    }

    @Test fun `share proofing safely cycles all three question photographs in both directions`() {
        val context = RuntimeEnvironment.getApplication()
        val photos = listOf(Color.RED, Color.GREEN, Color.BLUE).mapIndexed { index, color ->
            File(context.cacheDir, "export-proof-$index.png").apply {
                val bitmap = image(color)
                try { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
        }
        compose.setContent {
            MogeTheme { AnswerExportDialog(AnswerExportContent("题目", "检查多图", questionPhotos = photos.map { it.path }, answerText = "解答"), {}) }
        }
        awaitImage("待确认的题目照片 1")
        repeat(3) {
            compose.onNodeWithText("下一张照片").performScrollTo().performClick(); awaitImage("待确认的题目照片 2")
            compose.onNodeWithText("下一张照片").performScrollTo().performClick(); awaitImage("待确认的题目照片 3")
            compose.onNodeWithText("上一张照片").performScrollTo().performClick(); awaitImage("待确认的题目照片 2")
            compose.onNodeWithText("上一张照片").performScrollTo().performClick(); awaitImage("待确认的题目照片 1")
        }
    }
    @Test fun `share proofing inherits readable chalk text rather than default black`() {
        var expected = UiColor.Unspecified
        compose.setContent {
            MogeTheme(Appearance.CHALK) {
                expected = MaterialTheme.colorScheme.onBackground
                AnswerExportDialog(AnswerExportContent("黑板主题分享", "题目正文", answerText = "解答"), {})
            }
        }
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("题目正文").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue(action(layout))
        }
        assertEquals(expected, layout.single().layoutInput.style.color)
    }

}
