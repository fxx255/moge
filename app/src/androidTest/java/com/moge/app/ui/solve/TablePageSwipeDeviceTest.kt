package com.moge.app.ui.solve

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moge.app.ui.components.PageSwipeSurface
import com.moge.app.ui.markdown.MarkdownTableBlock
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TablePageSwipeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tableUsesPageSwipesOnlyWhileItsColumnsFitTheViewport() {
        val narrow = "| 题号 | 估分 | 主要失分点 |\n|---|---|---|\n| 46 | 1 | shaky 译错，to the effect that 漏译 |"
        val wide = "| A | B | C | D | E | F |\n|---|---|---|---|---|---|\n| 内容一 | 内容二 | 内容三 | 内容四 | 内容五 | 最后一列 |"
        val source = mutableStateOf(narrow)
        var left = 0
        var right = 0
        compose.setContent { MogeTheme {
            PageSwipeSurface(onLeft = { left++ }, onRight = { right++ }, modifier = Modifier.width(340.dp)) {
                MarkdownTableBlock(source.value)
            }
        } }
        val scroll = compose.onNodeWithTag("markdown-table-scroll")
        scroll.performTouchInput { swipeLeft() }
        scroll.performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(1, left); assertEquals(1, right); source.value = wide }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("markdown-table-cell-0-5").fetchSemanticsNode().positionInRoot.x
        scroll.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("markdown-table-cell-0-5").fetchSemanticsNode().positionInRoot.x < before)
        compose.runOnIdle { assertEquals(1, left); assertEquals(1, right); source.value = narrow }
        compose.waitForIdle()
        scroll.performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(2, left); assertEquals(1, right) }
    }
}
