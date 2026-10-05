package com.moge.app.ui.solve

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import android.view.MotionEvent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.moge.app.ui.document.DocumentCards
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.components.GridPaper
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AttachmentComposerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `attachment upload and send keep capsule and editor geometry stable`() {
        val state = mutableStateOf(SolveUiState())
        compose.setContent { MogeTheme {
            Box(Modifier.width(360.dp).height(500.dp), contentAlignment = Alignment.BottomCenter) {
                FollowUpBar(state.value, { state.value = state.value.copy(input = it) },
                    { state.value = state.value.copy(input = "", documentPaths = emptyList(), generating = true) },
                    {}, {}, {}, {}, {})
            }
        } }
        val capsule = compose.onNodeWithTag("composer-capsule").fetchSemanticsNode().boundsInRoot
        val editor = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(documentPaths = listOf("/long/document.docx")) }
        val attached = compose.onNodeWithTag("composer-capsule").fetchSemanticsNode().boundsInRoot
        assertEquals(capsule.size, attached.size)
        assertEquals(editor.width, compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.width, .1f)
        compose.onNodeWithContentDescription("发送").performClick()
        assertEquals(capsule.size, compose.onNodeWithTag("composer-capsule").fetchSemanticsNode().boundsInRoot.size)
        assertEquals(editor.width, compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.width, .1f)
        compose.onNodeWithContentDescription("停止生成").assertExists()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `fan actions appear above plus and each action invokes its own picker once`() {
        var camera = 0; var photos = 0; var documents = 0
        compose.setContent { MogeTheme(appearance = Appearance.CHALK) {
            GridPaper(Modifier.width(360.dp).height(500.dp)) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    FollowUpBar(SolveUiState(), {}, {}, {}, { camera++ }, { photos++ }, {}, {},
                        onPickDocument = { documents++ })
                }
            }
        } }
        compose.onNodeWithContentDescription("拍照").assertDoesNotExist()
        val bounds = compose.onNodeWithTag("composer-capsule").fetchSemanticsNode().boundsInWindow
        listOf("拍照", "从相册选图", "上传文档").forEach { description ->
            compose.onNodeWithContentDescription("添加附件").performClick()
            val action = compose.onNodeWithContentDescription(description)
            action.assertIsEnabled()
            assertTrue(action.fetchSemanticsNode().boundsInWindow.bottom <= bounds.top + 1f)
            if (description == "拍照") exportPreview("attachment-fan")
            action.performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("拍照").assertDoesNotExist()
        }
        assertEquals(1, camera); assertEquals(1, photos); assertEquals(1, documents)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `question attachments render as bounded file cards with document icons`() {
        compose.setContent { MogeTheme(appearance = Appearance.CHALK) {
            GridPaper(Modifier.width(360.dp).height(500.dp)) {
                Box(Modifier.padding(16.dp)) {
                    DocumentCards(listOf("/lecture-with-a-long-name.docx", "/notes.pdf"))
                }
            }
        } }
        compose.onAllNodesWithTag("document-card").assertCountEquals(2)
        compose.onNodeWithContentDescription("Word 文档").assertExists()
        compose.onNodeWithContentDescription("PDF 文档").assertExists()
        compose.onNodeWithText("lecture-with-a-long-name.docx").assertExists()
        exportPreview("document-cards")
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `outside touch retracts fan without invoking a picker`() {
        var picks = 0
        compose.setContent { MogeTheme {
            Box(Modifier.width(360.dp).height(500.dp), contentAlignment = Alignment.BottomCenter) {
                FollowUpBar(SolveUiState(), {}, {}, {}, { picks++ }, { picks++ }, {}, {},
                    onPickDocument = { picks++ })
            }
        } }
        compose.onNodeWithContentDescription("添加附件").performClick()
        compose.runOnIdle {
            // Android's window manager delivers ACTION_OUTSIDE to the popup window.
            val popup = WindowInspector.getGlobalWindowViews().last()
            val event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                MotionEvent.ACTION_OUTSIDE, -10f, -10f, 0)
            try { popup.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("拍照").assertDoesNotExist()
        compose.onNodeWithContentDescription("添加附件").assertExists()
        compose.onNodeWithContentDescription("添加附件").performClick()
        compose.onNode(isPopup()).performTouchInput { click(Offset(6f, 8f)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("拍照").assertDoesNotExist()
        compose.onNodeWithContentDescription("添加附件").performClick()
        compose.onNodeWithContentDescription("收起附件菜单").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("拍照").assertDoesNotExist()
        assertEquals(0, picks)
    }

    private fun exportPreview(name: String) {
        if (System.getenv("MOGE_EXPORT_UI_SAMPLES") != "1") return
        compose.runOnIdle {
            val windows = WindowInspector.getGlobalWindowViews().filter { it.width > 0 && it.height > 0 }
            val positions = windows.map { view -> IntArray(2).also(view::getLocationOnScreen) }
            val left = positions.minOf { it[0] }; val top = positions.minOf { it[1] }
            val width = windows.indices.maxOf { positions[it][0] + windows[it].width } - left
            val height = windows.indices.maxOf { positions[it][1] + windows[it].height } - top
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            windows.forEachIndexed { index, view ->
                canvas.save(); canvas.translate((positions[index][0] - left).toFloat(), (positions[index][1] - top).toFloat())
                view.draw(canvas); canvas.restore()
            }
            File("build/ui-preview").mkdirs()
            File("build/ui-preview/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
