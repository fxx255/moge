package com.moge.app.ui.solve

import android.graphics.Bitmap
import android.graphics.Color
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Hardware shadows need a window screenshot; software Canvas snapshots omit them. */
@RunWith(AndroidJUnit4::class)
class AttachmentShadowDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun shadowExtendsOutsideActionBoundsWhileOpeningAndClosing() {
        compose.setContent { MogeTheme(Appearance.PAPER) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)
                .padding(64.dp), contentAlignment = Alignment.BottomCenter) {
                ComposerAttachmentMenu(true, true, {}, {}, {})
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("添加附件").performClick()
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.onNodeWithContentDescription("拍照").assertExists()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(240)
        assertShadow("opening")
        compose.mainClock.advanceTimeBy(160)
        assertShadow("open")
        compose.onNodeWithContentDescription("收起附件菜单").performClick()
        compose.mainClock.advanceTimeBy(64)
        assertShadow("closing")
        compose.mainClock.advanceTimeBy(240)
        compose.onNodeWithContentDescription("拍照").assertDoesNotExist()
        compose.onNodeWithContentDescription("添加附件").assertExists()
        compose.mainClock.autoAdvance = true
    }

    private fun assertShadow(name: String) {
        compose.waitForIdle()
        val localBounds = compose.onNode(hasClickAction() and hasAnyDescendant(hasContentDescription("拍照")),
            useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val position = IntArray(2)
        compose.runOnIdle {
            WindowInspector.getGlobalWindowViews().single { it.javaClass.simpleName == "PopupLayout" }
                .getLocationOnScreen(position)
        }
        val action = localBounds.translate(Offset(position[0].toFloat(), position[1].toFloat()))
        // Allow RenderThread to present the frozen Compose frame before reading screen pixels.
        android.os.SystemClock.sleep(100)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val output = File(instrumentation.targetContext.getExternalFilesDir(null), "shadow-$name.png")
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val density = compose.activity.resources.displayMetrics.density
            val darkness = darknessBelow(bitmap, action, density)
            assertTrue("$name: shadow must extend below the action, without a rectangular alpha crop ($action, darkness=$darkness)",
                darkness > 2f)
        } finally { bitmap.recycle() }
    }

    private fun darknessBelow(bitmap: Bitmap, bounds: Rect, density: Float): Float {
        val left = (bounds.center.x - 4f * density).toInt()
        val right = (bounds.center.x + 4f * density).toInt()
        val top = (bounds.bottom + 2f * density).toInt()
        val bottom = (bounds.bottom + 4f * density).toInt()
        return (top..bottom).flatMap { y -> (left..right).map { x ->
            val pixel = bitmap.getPixel(x, y)
            255f - (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3f
        } }.average().toFloat()
    }
}
