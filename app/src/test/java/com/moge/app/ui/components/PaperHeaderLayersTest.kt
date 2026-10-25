package com.moge.app.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.LocalPaperMotionEnabled
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h820dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaperHeaderLayersTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `reading passes over the tilted underlay and behind the front strip`() {
        lateinit var view: View
        var body by mutableStateOf(false)
        var scale = 1f
        var underlay = 0
        var front = 0
        compose.setContent { MogeTheme(Appearance.CHALK) {
            view = LocalView.current
            scale = LocalDensity.current.density
            underlay = MogeTheme.paper.headerUnderlay.toArgb()
            front = MogeTheme.paper.headerPaper.toArgb()
            PaperNavigationLayers {
                PaperScaffold("标题", null, headerStyle = PaperHeaderStyle.StackedLeft,
                    contentBehindHeader = true) {
                    if (body) Box(Modifier.fillMaxSize().background(Color.Red))
                }
            }
        } }
        compose.waitForIdle()
        val header = compose.onNodeWithTag("paper-strip-header").fetchSemanticsNode().boundsInRoot
        val x = (8 * scale).toInt()
        val below = (header.bottom + 10 * scale).toInt()
        assertEquals("The left lower edge is exposed on empty paper (bounds=$header, scale=$scale)", underlay, pixel(view, x, below))
        val upperGap = pixel(view, x, 1)
        compose.runOnIdle { body = true }
        assertEquals("Reading covers the underlay's exposed edge", Color.Red.toArgb(), pixel(view, x, below))
        assertEquals("The front sheet still covers reading", front, pixel(view, x, header.center.y.toInt()))
        assertEquals("No reading bleeds into the upper gap", upperGap, pixel(view, x, 1))
    }

    @Test fun `page navigation keeps a single opaque sheet while ink fades and reading slides`() = navigation(true)
    @Test fun `disabled animations settle all header and reading layers immediately`() = navigation(false)

    private fun navigation(motion: Boolean) {
        lateinit var nav: NavHostController
        lateinit var view: View
        var front = 0
        compose.setContent { MogeTheme(Appearance.PAPER) {
            CompositionLocalProvider(LocalPaperMotionEnabled provides motion) {
                view = LocalView.current
                front = MogeTheme.paper.headerPaper.toArgb()
                nav = rememberNavController()
                PaperNavigationLayers {
                    NavHost(nav, startDestination = "conversation", enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None }, popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None }) {
                        listOf("conversation", "history", "notebook", "settings").forEach { name ->
                            composable(name) {
                                PaperNavigationEntry(direction = 1) {
                                    PaperScaffold(name, null, contentBehindHeader = true,
                                        headerStyle = when (name) {
                                            "notebook" -> PaperHeaderStyle.StackedRight
                                            "settings" -> PaperHeaderStyle.Single
                                            else -> PaperHeaderStyle.StackedLeft
                                        }) {
                                        Box(Modifier.fillMaxSize().testTag("body-$name")
                                            .background(if (name == "conversation") Color.Red else Color.Blue))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        val original = compose.onNodeWithText("conversation").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        listOf("history", "notebook", "settings").forEach { name ->
            compose.runOnIdle { nav.navigate(name) }
            compose.mainClock.advanceTimeBy(32)
            repeat(5) {
                val title = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot
                assertEquals("Header ink never slides with the body", original.left, title.left, .1f)
                assertTrue("Only the small paper lift is allowed", kotlin.math.abs(original.top - title.top) <= 3f)
                assertEquals("The paper cannot fade out between entries", front, pixel(view, 2, original.center.y.toInt()))
                if (motion && it == 0) assertTrue(compose.onNodeWithTag("body-$name").fetchSemanticsNode().boundsInRoot.left < 0)
                if (!motion) assertEquals(0f, compose.onNodeWithTag("body-$name").fetchSemanticsNode().boundsInRoot.left, .1f)
                compose.mainClock.advanceTimeBy(48)
            }
            compose.mainClock.advanceTimeBy(600)
        }
        compose.mainClock.autoAdvance = true
    }

    private fun pixel(view: View, x: Int, y: Int): Int {
        var color = 0
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try { view.draw(Canvas(bitmap)); color = bitmap.getPixel(x, y) } finally { bitmap.recycle() }
        }
        return color
    }
}
