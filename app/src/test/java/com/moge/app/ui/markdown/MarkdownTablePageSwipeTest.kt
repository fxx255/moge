package com.moge.app.ui.markdown

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.moge.app.ui.components.PageSwipeSurface
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w400dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownTablePageSwipeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fittingTableNavigatesBothWaysAndWidthChangesRemoveOldExclusions() {
        val wide = "| A | B | C | D | E | F |\n|---|---|---|---|---|---|\n| 内容一 | 内容二 | 内容三 | 内容四 | 内容五 | 最后一列 |"
        val narrow = "| 题号 | 估分 | 主要失分点 |\n|---|---|---|\n| 46 | 1 | shaky 译错，to the effect that 漏译 |"
        val source = mutableStateOf(wide)
        var left = 0
        var right = 0
        var density = 1f
        compose.setContent { MogeTheme {
            density = LocalDensity.current.density
            PageSwipeSurface(onLeft = { left++ }, onRight = { right++ }, modifier = Modifier.width(340.dp)) {
                MarkdownTableBlock(source.value)
            }
        } }
        val scroll = compose.onNodeWithTag("markdown-table-scroll")
        val before = compose.onNodeWithTag("markdown-table-cell-0-5").fetchSemanticsNode().positionInRoot.x
        scroll.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("markdown-table-cell-0-5").fetchSemanticsNode().positionInRoot.x
        assertTrue("Wide tables must keep their own scrolling", after < before)
        assertEquals(0, left)
        assertEquals(0, right)
        compose.runOnIdle { source.value = narrow }
        compose.waitForIdle()
        fun drag(direction: Int) {
            scroll.performTouchInput {
                down(center)
                moveTo(center + Offset(direction * 90f * density, 0f), delayMillis = 100)
                up()
            }
        }
        drag(-1)
        drag(1)
        compose.runOnIdle { assertEquals(1, left); assertEquals(1, right) }
        // A long press belongs to native selection, even on a fitting table.
        scroll.performTouchInput {
            down(center)
            advanceEventTime(650)
            moveTo(center + Offset(-90f * density, 0f))
            up()
        }
        compose.runOnIdle { assertEquals(1, left); assertEquals(1, right) }
    }
}
