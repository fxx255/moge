package com.moge.app.ui.markdown

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MarkdownAppearanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }
    @Test fun `changing font scale and theme recreates markdown and formula renderer`() {
        val large = mutableStateOf(false)
        var expectedSize = 0f
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large.value) 2f else 1f)) {
                expectedSize = with(LocalDensity.current) { MARKDOWN_TEXT_SIZE_SP.sp.toPx() }
                MogeTheme(if (large.value) Appearance.CHALK else Appearance.PAPER) {
                    MarkdownChunk("字号测试", fixedWidthPx = null)
                }
            }
        }
        compose.waitForIdle()
        val root = compose.activity.findViewById<ViewGroup>(android.R.id.content)
        val old = textViews(root).single()
        compose.runOnIdle { large.value = true }
        compose.waitForIdle()
        val current = textViews(root).single()
        assertNotSame(old, current)
        // Android 的辅助字号是非线性映射，不能假定 2 倍设置恰好得到 2 倍像素。
        assertEquals(expectedSize, current.textSize, 0.1f)
        assertTrue(current.textSize > old.textSize)
        assertNotEquals(old.currentTextColor, current.currentTextColor)
    }
}
