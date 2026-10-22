package com.moge.app.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h820dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaperSearchPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `paper filters reveal the clicked card at either edge with minimal scrolling`() = revealFilter(Appearance.PAPER, 1f)
    @Test fun `large chalk filters reveal the clicked card at either edge with minimal scrolling`() = revealFilter(Appearance.CHALK, 1.8f)
    @Test fun `paper sheet hides the top content sliver while leaving the list visible below`() = verifySeam(Appearance.PAPER)
    @Test fun `chalk sheet hides the top content sliver while leaving the list visible below`() = verifySeam(Appearance.CHALK)

    @Test fun `floating toolbar decoration can extend above the content viewport`() {
        lateinit var view: View
        compose.setContent { MogeTheme(Appearance.PAPER) {
            view = LocalView.current
            PaperScaffold("历史对话", onBack = {}) {
                FloatingPaperToolbar(Modifier.fillMaxSize().testTag("viewport"), toolbar = {
                    // Software snapshots omit platform elevation shadows. This
                    // overhang verifies that ancestors leave the same space free.
                    Box(Modifier.fillMaxWidth().height(40.dp).drawBehind {
                        drawRect(Color.Green, Offset(0f, -8.dp.toPx()), Size(size.width, 8.dp.toPx()))
                    })
                }) { Box(Modifier.fillMaxSize().background(Color.Magenta)) }
            }
        } }
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val y = (viewport.top - 4 * view.resources.displayMetrics.density).roundToInt()
                assertEquals("The upper shadow must not be cut at the viewport boundary",
                    android.graphics.Color.GREEN, bitmap.getPixel(viewport.center.x.roundToInt(), y))
            } finally { bitmap.recycle() }
        }
    }

    private fun revealFilter(appearance: Appearance, fontScale: Float) {
        val labels = listOf("全部", "未分类", "高数", "通信原理", "英语", "物理", "化学", "生物", "政治", "历史", "地理")
        val selected = mutableIntStateOf(0)
        var density = 1f
        compose.setContent {
            val current = LocalDensity.current
            density = current.density
            CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale)) {
                MogeTheme(appearance) {
                    PaperSearchPanel("", {}, "搜索", enabled = true, filtersModifier = Modifier.testTag("filters")) {
                        labels.forEachIndexed { index, label ->
                            PaperCategoryFilter(selected.intValue == index, { selected.intValue = index }, label, enabled = true)
                        }
                    }
                }
            }
        }
        val row = compose.onNodeWithTag("filters")
        val chip = compose.onNode(hasText("英语") and hasClickAction())
        val viewport = row.getUnclippedBoundsInRoot()
        val initial = chip.getUnclippedBoundsInRoot()
        fun scrollBy(dp: Float) {
            row.performSemanticsAction(SemanticsActions.ScrollBy) { it(dp * density, 0f) }
            compose.waitForIdle()
        }
        fun assertFullyVisible() {
            val bounds = chip.getUnclippedBoundsInRoot()
            assertTrue("The whole selected chip must fit inside the filter viewport: $bounds / $viewport",
                bounds.left.value >= viewport.left.value - 0.5f && bounds.right.value <= viewport.right.value + 0.5f)
        }

        val chipWidth = (initial.right - initial.left).value
        scrollBy(initial.left.value - viewport.right.value + chipWidth / 2f)
        val clippedRight = chip.getUnclippedBoundsInRoot()
        assertTrue(clippedRight.left < viewport.right && clippedRight.right > viewport.right)
        chip.performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(4, selected.intValue)
        assertFullyVisible()
        val revealed = chip.getUnclippedBoundsInRoot()
        assertEquals("Reveal only the clipped edge", viewport.right.value, revealed.right.value, 1f)

        chip.performClick()
        compose.waitForIdle()
        assertEquals("A fully visible selection must not move", revealed, chip.getUnclippedBoundsInRoot())
        scrollBy(revealed.left.value - viewport.left.value + chipWidth / 2f)
        val clippedLeft = chip.getUnclippedBoundsInRoot()
        assertTrue(clippedLeft.left < viewport.left && clippedLeft.right > viewport.left)
        // Re-selecting the current filter must also reveal it after a manual swipe.
        chip.performTouchInput { click(center) }
        compose.waitForIdle()
        assertFullyVisible()
        assertEquals("Reveal only the clipped edge", viewport.left.value, chip.getUnclippedBoundsInRoot().left.value, 1f)
    }

    private fun verifySeam(appearance: Appearance) {
        lateinit var view: View
        val cardColor = mutableStateOf(Color.Magenta)
        compose.setContent { MogeTheme(appearance) {
            view = LocalView.current
            PaperScaffold("历史对话", onBack = {}) {
                FloatingPaperToolbar(Modifier.fillMaxSize().testTag("viewport"), toolbar = {
                    PaperSearchPanel("", {}, "搜索", enabled = true) {
                        PaperCategoryFilter(true, {}, "全部", enabled = true)
                    }
                }) {
                    // Stand in for a photo card scrolled directly beneath the search sheet.
                    Box(Modifier.fillMaxSize().background(cardColor.value))
                }
            }
        } }
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("paper-search-panel").fetchSemanticsNode().boundsInRoot
        fun snapshot(): Bitmap {
            lateinit var bitmap: Bitmap
            compose.runOnIdle {
                bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
            }
            return bitmap
        }
        val before = snapshot()
        compose.runOnIdle { cardColor.value = Color.Cyan }
        val after = snapshot()
        try {
            val x = viewport.center.x.roundToInt()
            val top = viewport.top.roundToInt()
            val seamHeight = (4 * view.resources.displayMetrics.density).roundToInt()
            for (y in top until top + seamHeight) {
                assertTrue("Scrolling cards must not leak through the header seam ($appearance, y=$y)",
                    before.getPixel(x, y) != android.graphics.Color.MAGENTA)
            }
            assertEquals("The floating sheet must leave the content beneath it visible",
                android.graphics.Color.MAGENTA, before.getPixel(x, panel.bottom.roundToInt() + seamHeight + 4))
            assertEquals(android.graphics.Color.CYAN, after.getPixel(x, panel.bottom.roundToInt() + seamHeight + 4))
            // Changing a card beneath the sheet must not change the paper
            // visible through either upper rounded cutout, even under a shadow.
            val cornerSize = (16 * view.resources.displayMetrics.density).roundToInt()
            for (cornerLeft in listOf(panel.left.roundToInt(), panel.right.roundToInt() - cornerSize)) {
                for (cornerX in cornerLeft until cornerLeft + cornerSize) {
                    for (cornerY in top until top + cornerSize) {
                        assertEquals("Card pixels leak through an upper rounded corner ($appearance, x=$cornerX, y=$cornerY)",
                            before.getPixel(cornerX, cornerY), after.getPixel(cornerX, cornerY))
                    }
                }
            }
        } finally { before.recycle(); after.recycle() }
    }
}
