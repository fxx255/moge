package com.moge.app.ui.solve

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.markdown.markdownSelectionController
import com.moge.app.ui.markdown.selectionEndpoint
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Inject into the actual popup windows, including a full-line selection next to a photo. */
@RunWith(AndroidJUnit4::class)
class QuestionTextSelectionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var displayId = Display.DEFAULT_DISPLAY

    @Test fun singleLineRightHandleShrinksWithoutRebounding() = verify("帮我纠错，详细讲讲。", first = true)
    @Test fun photoQuestionRightHandleShrinksWithoutRebounding() = verify("帮我纠错，详细讲讲，注重原理和一般结论。", first = true, photo = true)
    @Test fun largeFollowUpRightHandleShrinksAndRetainsTapToEdit() = verify("帮我纠错，详细讲讲，注重原理和一般结论。", first = false, fontScale = 1.6f)
    @Test fun rightHandleCanCrossLeftHandleAndReturn() = verify("帮我纠错，详细讲讲，注重原理。", first = false, crossing = true)

    private fun verify(raw: String, first: Boolean, photo: Boolean = false, fontScale: Float = 1f, crossing: Boolean = false) {
        val appearance = mutableStateOf(Appearance.PAPER)
        val callbackVersion = mutableStateOf(0)
        var edits = 0
        var imageOpens = 0
        val image = if (photo) File(instrumentation.targetContext.cacheDir, "question-selection-photo.png").also { file ->
            val bitmap = Bitmap.createBitmap(600, 140, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.LTGRAY)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        } else null
        compose.setContent { MogeTheme(appearance.value) {
            val density = LocalDensity.current
            val version = callbackVersion.value
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SolveList(SolveUiState(conversationId = "question", items = listOf(
                    SolveItem.Question("q", raw, listOfNotNull(image?.absolutePath), "", first))),
                    {}, {}, { _, _ -> imageOpens++ }, {}, {}, onEdit = { edits += version + 1 })
            }
        } }
        try {
            for (theme in listOf(Appearance.PAPER, Appearance.CHALK)) {
                compose.runOnUiThread { appearance.value = theme }
                compose.waitForIdle()
                compose.onNodeWithText(raw).performScrollTo()
                compose.waitForIdle()
                lateinit var body: TextView
                compose.waitUntil(10_000) {
                    var ready = false
                    instrumentation.runOnMainSync {
                        views(compose.activity.window.decorView).singleOrNull { it.text.toString() == raw }?.let {
                            if (it.isLaidOut && it.isShown && it.hasWindowFocus() &&
                                it.getGlobalVisibleRect(android.graphics.Rect())) { body = it; ready = true }
                        }
                    }
                    ready
                }
                var point = 0f to 0f
                instrumentation.runOnMainSync {
                    displayId = body.display.displayId
                    val caret = requireNotNull(selectionEndpoint(body, 2, true))
                    val position = IntArray(2).also(body::getLocationOnScreen)
                    point = position[0] + caret.x to position[1] + caret.centerY
                }
                var time = SystemClock.uptimeMillis()
                inject(MotionEvent.ACTION_DOWN, point, time)
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150L)
                inject(MotionEvent.ACTION_UP, point, time)
                lateinit var selectedText: CharSequence
                var targets = emptyList<Pair<Float, Float>>()
                val anchor = if (crossing) 5 else 0
                val offsets = if (crossing) listOf(3, 2, 8, 10) else (raw.lastIndex downTo 2).toList()
                instrumentation.runOnMainSync {
                    val selection = requireNotNull(markdownSelectionController(body))
                    assertTrue("Long press must establish the shared question selection", selection.selecting)
                    selection.begin(anchor, raw.length)
                    selectedText = body.text
                    val caret = requireNotNull(selectionEndpoint(body, raw.length, false))
                    val position = IntArray(2).also(body::getLocationOnScreen)
                    val dx = 8f * body.resources.displayMetrics.density
                    val dy = 12f * body.resources.displayMetrics.density
                    point = position[0] + caret.x + dx to position[1] + caret.bottom + dy
                    targets = offsets.map { offset ->
                        val destination = requireNotNull(selectionEndpoint(body, offset, true))
                        position[0] + destination.x + dx to
                            position[1] + destination.centerY + caret.bottom - caret.centerY + dy
                    }
                }
                awaitFrame(body)
                time = SystemClock.uptimeMillis()
                inject(MotionEvent.ACTION_DOWN, point, time)
                instrumentation.runOnMainSync {
                    assertTrue("Touching the visible knob must start dragging", requireNotNull(markdownSelectionController(body)).dragging)
                }
                targets.forEachIndexed { index, target ->
                    inject(MotionEvent.ACTION_MOVE, target, time)
                    instrumentation.runOnMainSync {
                        val offset = offsets[index]
                        assertEquals(minOf(anchor, offset), body.selectionStart)
                        assertEquals("Each movement must follow the character under the finger", maxOf(anchor, offset), body.selectionEnd)
                        assertTrue(requireNotNull(markdownSelectionController(body)).dragging)
                    }
                }
                inject(MotionEvent.ACTION_UP, targets.last(), time)
                compose.runOnUiThread { callbackVersion.value++ }
                compose.waitForIdle()
                awaitFrame(body)
                instrumentation.runOnMainSync {
                    val offset = offsets.last()
                    assertSame("Updating callbacks must preserve selected text", selectedText, body.text)
                    assertEquals(minOf(anchor, offset), body.selectionStart)
                    assertEquals("Release must retain the adjusted endpoint", maxOf(anchor, offset), body.selectionEnd)
                    assertEquals("Selection must not edit or open the attachment", 0, edits)
                    assertEquals(0, imageOpens)
                    body.onTextContextMenuItem(android.R.id.copy)
                    val clipboard = body.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    assertEquals(raw.substring(minOf(anchor, offset), maxOf(anchor, offset)),
                        clipboard.primaryClip!!.getItemAt(0).text.toString())
                    val caret = requireNotNull(selectionEndpoint(body, 2, true))
                    val position = IntArray(2).also(body::getLocationOnScreen)
                    point = position[0] + caret.x to position[1] + caret.centerY
                }
                if (!first) {
                    time = SystemClock.uptimeMillis()
                    inject(MotionEvent.ACTION_DOWN, point, time)
                    inject(MotionEvent.ACTION_UP, point, time)
                    instrumentation.runOnMainSync {
                        assertEquals("An ordinary tap must still edit exactly once", callbackVersion.value + 1, edits)
                        edits = 0
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                views(compose.activity.window.decorView).forEach { markdownSelectionController(it)?.finish() }
            }
            image?.delete()
        }
    }

    private fun inject(action: Int, point: Pair<Float, Float>, time: Long) {
        val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, point.first, point.second, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        if (displayId != Display.DEFAULT_DISPLAY) {
            android.view.InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
        }
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private fun awaitFrame(view: View) {
        val frame = CountDownLatch(1)
        instrumentation.runOnMainSync { view.postOnAnimation { frame.countDown() } }
        assertTrue("Native popup placement must present a frame", frame.await(2, TimeUnit.SECONDS))
    }

    private fun views(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
        else -> emptyList()
    }
}
