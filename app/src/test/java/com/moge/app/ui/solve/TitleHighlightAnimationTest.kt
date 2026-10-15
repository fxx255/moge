package com.moge.app.ui.solve

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.moge.app.data.prefs.Appearance
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TitleHighlightAnimationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `mode fade retains the highlighter ends outside the text in both themes`() = preview(false)
    @Test fun `title writing retains the leading highlighter end during its reveal`() = preview(true)

    private fun preview(writing: Boolean) {
        var mode by mutableStateOf<ConversationMode?>(if (writing) null else ConversationMode.SOLVE)
        var title by mutableStateOf("旧主题")
        var theme by mutableStateOf(Appearance.CHALK)
        lateinit var view: View
        compose.setContent { MogeTheme(theme) {
            view = LocalView.current
            Box(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background).padding(32.dp)) {
                ConversationTitle(title, mode)
            }
        } }
        compose.onNodeWithText(if (writing) "旧主题" else "新题目").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        for (appearance in listOf(Appearance.CHALK, Appearance.PAPER)) {
            compose.runOnIdle {
                theme = appearance
                if (writing) title = if (appearance == Appearance.CHALK) "正在输入新的对话主题" else "又一个对话主题"
                else mode = if (appearance == Appearance.CHALK) ConversationMode.CHAT else ConversationMode.SOLVE
            }
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(160)
            val text = if (writing) title else if (mode == ConversationMode.CHAT) "新对话" else "新题目"
            val bounds = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode(
                errorMessageOnFail = compose.onRoot(useUnmergedTree = true).printToString()).boundsInRoot
            compose.runOnIdle {
                val root = view.rootView
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                try {
                    root.draw(Canvas(bitmap))
                    val rootLocation = IntArray(2).also { root.getLocationOnScreen(it) }
                    val viewLocation = IntArray(2).also { view.getLocationOnScreen(it) }
                    val textLeft = (bounds.left + viewLocation[0] - rootLocation[0]).toInt()
                    val top = (bounds.top + viewLocation[1] - rootLocation[1]).toInt()
                    var overhang = 0
                    for (y in top.coerceAtLeast(0) until (top + bounds.height).toInt().coerceAtMost(bitmap.height)) {
                        for (x in (textLeft - 5).coerceAtLeast(0) until textLeft - 1) {
                            val pixel = bitmap.getPixel(x, y)
                            if (Color.red(pixel) - Color.blue(pixel) > 18 && Color.green(pixel) - Color.blue(pixel) > 10) overhang++
                        }
                    }
                    if (System.getenv("PHOTO_UI_EXPORT_SAMPLES") == "1") {
                        val file = File("build/photo-ui-samples/title-${appearance.name.lowercase()}-${if (writing) "writing" else "switch"}.png")
                        file.parentFile?.mkdirs()
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    assertTrue("The animated layer must include the uneven leading end ($appearance, writing=$writing, textLeft=$textLeft, top=$top)", overhang > 2)
                } finally { bitmap.recycle() }
            }
            compose.mainClock.advanceTimeBy(800)
        }
        compose.mainClock.autoAdvance = true
    }
}
