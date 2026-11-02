package com.moge.app.ui.settings

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moge.app.data.prefs.UserSettings
import com.moge.app.ui.markdown.MarkdownChunk
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w1280dp-h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DisplaySettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `body size changes native text while scale changes the entire interface and retains system fonts`() {
        var prefs by mutableStateOf(UserSettings())
        var fontScale = 0f
        var largeTextPx = 0f
        var baseWidthDp = 0
        var layoutWidthDp = 0
        var baseDensity = 0f
        compose.setContent {
            val density = LocalDensity.current
            baseDensity = density.density
            baseWidthDp = LocalConfiguration.current.screenWidthDp
            largeTextPx = with(Density(density.density, 1.3f)) { 24.sp.toPx() }
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                MogeTheme(bodyTextSizeSp = prefs.bodyTextSizeSp, uiScalePercent = prefs.uiScalePercent) {
                    fontScale = LocalDensity.current.fontScale
                    layoutWidthDp = LocalConfiguration.current.screenWidthDp
                    Column {
                        DisplaySettingsSection(prefs, { prefs = prefs.copy(bodyTextSizeSp = it) },
                            { prefs = prefs.copy(uiScalePercent = it) })
                        Box(Modifier.size(48.dp).testTag("control-size"))
                        MarkdownChunk("正文预览与公式 \$\$x^2+1\$\$", null)
                    }
                }
            }
        }
        fun textSize(): Float {
            compose.waitForIdle()
            return native(compose.activity.window.decorView).first { it.text.contains("正文预览") }.textSize
        }
        fun controlWidth() = compose.onNodeWithTag("control-size").fetchSemanticsNode().boundsInRoot.width
        fun choose(tag: String, value: String) {
            compose.onNodeWithTag(tag).performClick()
            compose.onNode(hasText(value) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
            compose.onNode(isPopup()).assertDoesNotExist()
        }
        val originalText = textSize()
        val originalControl = controlWidth()
        choose("body-text-size-selector", "24")
        // System font enlargement can be nonlinear: match Compose's sp conversion,
        // rather than assuming the same multiplier for every text size.
        assertEquals(largeTextPx, textSize(), 0.1f)
        assertEquals(originalControl, controlWidth(), 0.1f)
        choose("interface-scale-selector", "150%")
        assertEquals(originalControl * 1.5f, controlWidth(), 1f)
        assertEquals(largeTextPx * 1.5f, textSize(), 0.1f)
        assertEquals(baseWidthDp / 1.5f, layoutWidthDp.toFloat(), 1f)
        val nativeDensity = native(compose.activity.window.decorView)
            .first { it.text.contains("正文预览") }.resources.displayMetrics.density
        assertEquals(baseDensity * 1.5f, nativeDensity, 0.01f)
        choose("interface-scale-selector", "75%")
        assertEquals(originalControl * 0.75f, controlWidth(), 1f)
        choose("interface-scale-selector", "100%")
        choose("body-text-size-selector", "17")
        assertEquals(originalText, textSize(), 0.1f)
        assertEquals(1.3f, fontScale, 0f)
    }

    private fun native(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { native(view.getChildAt(it)) }
        else -> emptyList()
    }
}
