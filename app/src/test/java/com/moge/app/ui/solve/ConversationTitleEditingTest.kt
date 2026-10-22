package com.moge.app.ui.solve

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.moge.app.data.prefs.Appearance
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.theme.LocalPaperMotionEnabled
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h820dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationTitleEditingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `long press edits in the header focuses keyboard and keeps draft through external title changes`() {
        var title by mutableStateOf("英语阅读理解中长难句与选项的比较方法")
        var editing by mutableStateOf(false)
        var appearance by mutableStateOf(Appearance.PAPER)
        var keyboardShown = 0
        var keyboardHidden = 0
        var contentClicks = 0
        var composerClicks = 0
        var backClicks = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() { keyboardShown++ }
            override fun hide() { keyboardHidden++ }
        }
        lateinit var view: View
        compose.setContent { MogeTheme(appearance) {
            view = LocalView.current
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                ConversationScaffold(title, { backClicks++ }, conversationMode = ConversationMode.SOLVE,
                    editingTitle = editing, onTitleEditingChange = { editing = it },
                    onRenameTitle = { title = it; true },
                    actions = { ConversationActions(true, {}, {}, {}) },
                    footer = { Text("继续提问", Modifier.fillMaxWidth().height(56.dp)
                        .clickable { composerClicks++ }.testTag("test-composer")) }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).clickable { contentClicks++ }) {
                        Text("固定搭配 effects on sth. 表示对……的影响。", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        } }
        val before = compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("conversation-title").performTouchInput { longClick() }
        compose.onNodeWithTag("conversation-title-editor").assertIsFocused().performTextReplacement("我的英语阅读笔记")
        assertTrue(keyboardShown > 0)
        compose.onNodeWithContentDescription("设置").assertDoesNotExist()
        compose.onNodeWithText("对话主题").assertDoesNotExist()
        compose.onNodeWithContentDescription("取消修改主题").assertDoesNotExist()
        val sheet = compose.onNodeWithTag("conversation-title-sheet").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, header.top, 1f)
        assertTrue(sheet.top >= header.top && sheet.bottom <= header.bottom)
        compose.runOnIdle { title = "模型稍晚生成的自动主题" }
        compose.onNodeWithTag("conversation-title-editor").assertTextEquals("我的英语阅读笔记")
        export(view, "paper-editing")
        compose.runOnIdle { appearance = Appearance.CHALK }
        compose.onNodeWithTag("conversation-title-editor").assertIsFocused().assertTextEquals("我的英语阅读笔记")
        export(view, "chalk-editing")
        compose.onNodeWithContentDescription("保存主题").performClick()
        compose.onNodeWithTag("conversation-title-editor").assertDoesNotExist()
        compose.onNodeWithText("我的英语阅读笔记").assertIsDisplayed()
        compose.onNodeWithContentDescription("设置").assertIsDisplayed()
        compose.onNodeWithTag("conversation-title").performTouchInput { longClick() }
        compose.onNodeWithTag("conversation-title-editor").performTextReplacement("不保存的修改")
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithText("我的英语阅读笔记").assertIsDisplayed()
        assertEquals(0, contentClicks)
        assertTrue(keyboardHidden >= 2)
        compose.onNodeWithTag("conversation-title").performTouchInput { longClick() }
        compose.onNodeWithTag("test-composer").performTouchInput { click() }
        compose.onNodeWithTag("conversation-title-editor").assertDoesNotExist()
        assertEquals(0, composerClicks)
        compose.onNodeWithTag("conversation-title").performTouchInput { longClick() }
        compose.onNodeWithTag("conversation-header").performTouchInput { click(androidx.compose.ui.geometry.Offset(12f, 12f)) }
        compose.onNodeWithTag("conversation-title-editor").assertDoesNotExist()
        assertEquals(0, backClicks)
    }

    @Test fun `invalid names stay editable and pending saves cannot be submitted twice`() {
        var editing by mutableStateOf(true)
        var title by mutableStateOf("原主题")
        var calls = 0
        var accepted = CompletableDeferred<Boolean>()
        compose.setContent { MogeTheme {
            ConversationScaffold(title, null, editingTitle = editing, onTitleEditingChange = { editing = it },
                onRenameTitle = { calls++; accepted.await().also { saved -> if (saved) title = it } },
                actions = {}, footer = {}) {}
        } }
        val editor = compose.onNodeWithTag("conversation-title-editor")
        editor.performTextReplacement("   ")
        compose.onNodeWithContentDescription("保存主题").assertIsNotEnabled()
        editor.performTextReplacement("😀".repeat(81))
        compose.onNodeWithContentDescription("保存主题").assertIsNotEnabled()
        editor.performTextReplacement("新主题")
        editor.performImeAction()
        compose.onNodeWithContentDescription("保存主题").assertIsNotEnabled()
        compose.onRoot().performTouchInput { click(center) }
        editor.assertExists()
        assertEquals(1, calls)
        compose.runOnIdle { accepted.complete(false) }
        compose.onNodeWithText("主题未保存，请重试").assertIsDisplayed()
        editor.assertTextEquals("新主题")
        compose.runOnIdle { accepted = CompletableDeferred() }
        compose.onNodeWithContentDescription("保存主题").performClick()
        compose.runOnIdle { accepted.complete(true) }
        compose.onNodeWithText("新主题").assertIsDisplayed()
        editor.assertDoesNotExist()
        assertEquals(2, calls)
    }

    @Test fun `sticky note wraps long titles at large font size and keeps confirmation below text`() {
        compose.setContent { MogeTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                Box(Modifier.width(320.dp)) {
                    ConversationScaffold("长主题".repeat(26), null, editingTitle = true,
                        onRenameTitle = { true }, actions = {}, footer = {}) {}
                }
            }
        } }
        val editor = compose.onNodeWithTag("conversation-title-editor")
        val layouts = mutableListOf<TextLayoutResult>()
        editor.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Long titles must wrap beyond the old three-line limit", layouts.single().lineCount > 3)
        assertFalse("Wrapped text must remain fully laid out", layouts.single().hasVisualOverflow)
        val text = editor.fetchSemanticsNode().boundsInRoot
        val check = compose.onNodeWithContentDescription("保存主题").fetchSemanticsNode().boundsInRoot
        val sheet = compose.onNodeWithTag("conversation-title-sheet").fetchSemanticsNode().boundsInRoot
        assertTrue(check.top >= text.bottom)
        assertTrue(check.right <= sheet.right && check.bottom <= sheet.bottom)
        assertTrue(check.left > sheet.center.x && check.top > sheet.center.y)
        compose.onNodeWithContentDescription("保存主题").assertIsDisplayed()
    }

    @Test fun `only overflowing titles scroll and system motion preference stops them`() {
        val title = "英语阅读理解长难句和词义辨析的整理方法"
        var motion by mutableStateOf(true)
        lateinit var view: View
        compose.mainClock.autoAdvance = false
        compose.setContent { MogeTheme(Appearance.PAPER) {
            view = LocalView.current
            CompositionLocalProvider(LocalPaperMotionEnabled provides motion) {
                Column(Modifier.padding(24.dp)) {
                    Box(Modifier.width(180.dp)) { ConversationTitle(title) }
                    Box(Modifier.width(180.dp).testTag("short-title")) { ConversationTitle("英语阅读") }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(1_000)
        val shortBounds = compose.onNodeWithTag("short-title").fetchSemanticsNode().boundsInRoot
        val start = snapshot(view)
        compose.mainClock.advanceTimeBy(3_000)
        val moved = snapshot(view)
        assertFalse("Long titles should slowly expose their hidden text", start.sameAs(moved))
        val short = Bitmap.createBitmap(start, shortBounds.left.toInt(), shortBounds.top.toInt(),
            shortBounds.width.toInt(), shortBounds.height.toInt())
        val shortLater = Bitmap.createBitmap(moved, shortBounds.left.toInt(), shortBounds.top.toInt(),
            shortBounds.width.toInt(), shortBounds.height.toInt())
        assertTrue("A title that fits must not move", short.sameAs(shortLater))
        short.recycle(); shortLater.recycle()
        compose.onNodeWithText(title).assertExists()
        start.recycle(); moved.recycle()
        compose.runOnIdle { motion = false }
        compose.mainClock.advanceTimeBy(32)
        val stopped = snapshot(view)
        compose.mainClock.advanceTimeBy(4_000)
        val still = snapshot(view)
        assertTrue("Disabled animations must keep the title still", stopped.sameAs(still))
        stopped.recycle(); still.recycle()
    }

    private fun snapshot(view: View): Bitmap {
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        return bitmap
    }

    private fun export(view: View, name: String) {
        if (System.getenv("TITLE_UI_EXPORT_SAMPLES") != "1") return
        val bitmap = snapshot(view)
        try {
            val file = File("build/ui-preview/title-$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
