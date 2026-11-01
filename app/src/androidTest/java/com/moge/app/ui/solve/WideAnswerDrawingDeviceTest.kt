package com.moge.app.ui.solve

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.ui.theme.LocalPaperMotionEnabled
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Compare the actual hardware frame with a fresh native draw at the same scroll position. */
@RunWith(AndroidJUnit4::class)
class WideAnswerDrawingDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun native(view: View = compose.activity.window.decorView): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { native(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun scrollingLongWideAnswerKeepsEveryVisibleLinePainted() {
        val body = (0..100).joinToString("\n\n") {
            "第 $it 段：你的能量和噪声功率计算正确，但两处误码率以及有用信号值需要修改。\n\n" +
                "1. 信号能量：\$E_s = 4\$。\n2. 发送 1 和 0 时，有用信号值分别为 +4 和 -4；误码率为 \$P_e=\\frac{1}{2}\\operatorname{erfc}(\\frac{2}{\\sqrt{N_0}})\$。"
        }
        compose.setContent { MogeTheme {
            CompositionLocalProvider(LocalPaperMotionEnabled provides false) {
                SolveList(SolveUiState(conversationId = "wide", items = listOf(
                    SolveItem.Answer("a", AnswerState.COMPLETED, body))),
                    {}, {}, { _, _ -> }, {}, {}, initialViewport = ConversationViewport("a", 0, 0),
                    contentPadding = PaddingValues(16.dp))
            }
        } }
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(8) { step ->
            compose.onNodeWithTag("conversation-list").performTouchInput {
                click(Offset(130f, 180f))
            }
            compose.onNodeWithTag("conversation-list").performTouchInput {
                val delta = if (step < 4) -240f else 210f
                swipe(center, center + Offset(0f, delta), durationMillis = 600)
            }
            compose.waitForIdle()
            val before = requireNotNull(automation.takeScreenshot())
            val reference = Bitmap.createBitmap(before.width, before.height, Bitmap.Config.ARGB_8888)
            compose.runOnIdle {
                native().forEach { view ->
                    assertEquals("Native text must never scroll independently", 0, view.scrollY)
                    assertEquals(view.width - view.totalPaddingLeft - view.totalPaddingRight, view.layout.width)
                    assertTrue("Native text must reserve its entire layout height", view.height >= view.layout.height)
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    val canvas = Canvas(reference)
                    canvas.translate(location[0].toFloat(), location[1].toFloat())
                    view.draw(canvas)
                    view.invalidate()
                }
            }
            compose.waitForIdle()
            val fresh = requireNotNull(automation.takeScreenshot())
            try {
                var changed = 0
                var missing = 0
                // Exclude the status/navigation bars, which are unrelated to the answer.
                for (y in 100 until before.height - 100) for (x in 100 until before.width - 100) {
                    if (before.getPixel(x, y) != fresh.getPixel(x, y)) changed++
                    val expected = reference.getPixel(x, y)
                    if (Color.alpha(expected) > 200 && Color.red(expected) < 120) {
                        val actual = before.getPixel(x, y)
                        if (Color.red(actual) > 200 && Color.green(actual) > 200 && Color.blue(actual) > 200) missing++
                    }
                }
                if (changed > 30 || missing > 30) {
                    val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
                    for ((name, bitmap) in listOf("before" to before, "fresh" to fresh, "reference" to reference)) {
                        File(directory, "wide-answer-$step-$name.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                }
                assertTrue("Scroll $step left stale clipped text; native invalidation changed $changed pixels", changed <= 30)
                assertTrue("Scroll $step clipped $missing text pixels from the native layout", missing <= 30)
            } finally { before.recycle(); fresh.recycle(); reference.recycle() }
        }
    }
}
