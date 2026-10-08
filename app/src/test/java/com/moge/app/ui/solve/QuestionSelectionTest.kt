package com.moge.app.ui.solve

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Magnifier
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.moge.app.ui.theme.MogeTheme
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import ru.noties.jlatexmath.JLatexMathAndroid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalFoundationApi::class)
class QuestionSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun initializeMath() { JLatexMathAndroid.init(compose.activity) }
    private fun clipboard() = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private class CopyToolbar : TextContextMenuProvider {
        var copy: (() -> Unit)? = null
        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            val closed = CompletableDeferred<Unit>()
            val session = object : TextContextMenuSession {
                override fun close() { closed.complete(Unit) }
            }
            val item = dataProvider.data().components.filterIsInstance<TextContextMenuItem>()
                .singleOrNull { it.key == TextContextMenuKeys.CopyKey }
            copy = item?.let { { it.onClick(session) } }
            closed.await()
        }
    }

    @Test
    @Config(shadows = [SelectionTestMagnifier::class])
    fun `long press selects question text without starting edit and copies selected word`() {
        var edits = 0
        val raw = "original chosen question"
        val toolbar = CopyToolbar()
        compose.setContent { MogeTheme {
            CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) {
                FollowUpNote(SolveItem.Question("q", raw, emptyList(), "", false), onEdit = { edits++ }) { _, _ -> }
            }
        } }
        val node = compose.onNodeWithText(raw)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val chosen = layouts.single().getBoundingBox(raw.indexOf("chosen") + 2).center
        node.performTouchInput { longClick(chosen) }
        // Smart selection/classification returns asynchronously from Android.
        compose.waitUntil(timeoutMillis = 5_000) { toolbar.copy != null }
        compose.runOnIdle {
            assertEquals(0, edits)
            assertNotNull("Long press must offer selection copy", toolbar.copy)
            toolbar.copy!!.invoke()
        }
        compose.runOnIdle { assertEquals("chosen", clipboard().primaryClip!!.getItemAt(0).text.toString()) }
    }

    @Test fun `math question native long press retains selection and does not edit`() {
        var edits = 0
        compose.setContent { MogeTheme {
            FollowUpNote(SolveItem.Question("q", "前文 \$\$x^2+y^2\$\$ 后文", emptyList(), "", false),
                onEdit = { edits++ }) { _, _ -> }
        } }
        compose.runOnIdle {
            val root = compose.activity.findViewById<ViewGroup>(android.R.id.content)
            val view = nativeMathViews(root).single()
            assertTrue(view.isTextSelectable)
            val span = nativeMathSpans(view).single()
            val text = view.text as Spannable
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            val line = view.layout.getLineForOffset(start)
            val x = view.layout.getPrimaryHorizontal(start) + 1f
            val y = (view.layout.getLineTop(line) + view.layout.getLineBottom(line)) / 2f
            var downTime = SystemClock.uptimeMillis()
            fun dispatch(action: Int) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            dispatch(MotionEvent.ACTION_DOWN)
            dispatch(MotionEvent.ACTION_UP)
            assertEquals("An ordinary native text tap must edit exactly once", 1, edits)
            edits = 0
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            downTime = SystemClock.uptimeMillis()
            dispatch(MotionEvent.ACTION_DOWN)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            dispatch(MotionEvent.ACTION_UP)
            assertEquals(0, edits)
            assertTrue("Native long press must select text", view.hasSelection())
            // Exact formula selection must also suppress the input's tap-to-edit listener.
            Selection.setSelection(text, start + 1, end - 1)
            view.performClick()
            assertEquals(0, edits)
            view.onTextContextMenuItem(android.R.id.copy)
            assertEquals("x^2+y^2", clipboard().primaryClip!!.getItemAt(0).text.toString())
        }
    }
}

/** Robolectric has no Surface for the floating magnifier; selection itself runs normally. */
@Implements(Magnifier::class, minSdk = 28)
class SelectionTestMagnifier {
    @Implementation fun show(x: Float, y: Float) = Unit
    @Implementation fun show(x: Float, y: Float, windowX: Float, windowY: Float) = Unit
    @Implementation fun update() = Unit
    @Implementation fun dismiss() = Unit
}
