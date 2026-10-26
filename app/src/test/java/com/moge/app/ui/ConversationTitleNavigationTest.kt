package com.moge.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.createGraph
import com.moge.app.data.prefs.Appearance
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.components.PaperNavigationLayers
import com.moge.app.ui.solve.ConversationActions
import com.moge.app.ui.solve.ConversationScaffold
import com.moge.app.ui.solve.paperConversationPageMotion
import com.moge.app.ui.theme.LocalPaperMotionEnabled
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Check rendered ink: full semantic text can still have a partially clipped title. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h820dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationTitleNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: NavHostController
    private lateinit var swipe: PaperSwipeNavigation
    private lateinit var view: View
    private var density = 1f
    private var title by mutableStateOf("满秩分解求矩阵的高次幂与线性相关关系")

    @Test fun `parked long title stays still throughout a held return drag`() {
        render(startOld = true)
        begin("new", 1, 1f)
        release()
        // The previous page remains composed; its marquee must not advance offscreen.
        compose.mainClock.advanceTimeBy(8_000)
        begin("old", -1, .85f)
        val first = header("parked-first")
        compose.mainClock.advanceTimeBy(800)
        val held = header("parked-held")
        try {
            assertTrue("A held finger must keep the incoming title ink still", first.sameAs(held))
        } finally { first.recycle(); held.recycle() }
        compose.runOnIdle { swipe.release(0f, cancelled = true) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals("new", nav.currentDestination?.route) }
        begin("old", -1, .85f)
        release()
        compose.runOnIdle { assertEquals("old", nav.currentDestination?.route) }
    }

    @Test fun `loading an incoming title paints its full viewport without a second animation`() =
        verifyLoadedTitle(Appearance.PAPER, 1f)

    @Test fun `large chalk title also arrives fully painted`() =
        verifyLoadedTitle(Appearance.CHALK, 1.5f)

    private fun verifyLoadedTitle(appearance: Appearance, fontScale: Float) {
        title = ""
        render(startOld = false, appearance, fontScale)
        begin("old", -1, .85f)
        compose.runOnIdle { title = "满秩分解求矩阵的高次幂与线性相关关系" }
        compose.mainClock.advanceTimeBy(64)
        val loaded = header("loaded-${appearance.name.lowercase()}")
        compose.mainClock.advanceTimeBy(800)
        val held = header("loaded-held-${appearance.name.lowercase()}")
        try {
            assertTrue("Loading a saved title during a drag must not replay fade, resize or writing", loaded.sameAs(held))
        } finally { loaded.recycle(); held.recycle() }
        release()
        val committed = header("committed-${appearance.name.lowercase()}")
        compose.mainClock.advanceTimeBy(800)
        val settled = header("settled-${appearance.name.lowercase()}")
        try {
            assertTrue("Completing the drag must not restart the title animation", committed.sameAs(settled))
        } finally { committed.recycle(); settled.recycle() }
    }

    private fun render(startOld: Boolean, appearance: Appearance = Appearance.PAPER, fontScale: Float = 1f) {
        compose.mainClock.autoAdvance = false
        compose.setContent { MogeTheme(appearance) {
            val localDensity = LocalDensity.current
            density = localDensity.density
            CompositionLocalProvider(LocalPaperMotionEnabled provides true,
                LocalDensity provides Density(localDensity.density, fontScale)) {
                view = LocalView.current
                nav = rememberNavController()
                val graph = remember(nav) { nav.createGraph(startDestination = if (startOld) "old" else "new") {
                    composable("old") {}; composable("new") {}
                } }
                PaperNavigationLayers {
                    PaperNavHost(nav, graph, null, hasComposer = { true }) { entry ->
                        swipe = checkNotNull(LocalPaperSwipeNavigation.current)
                        val old = entry.destination.route == "old"
                        ConversationScaffold(title, {},
                            actions = { ConversationActions(old, {}, {}, {}) }, footer = {},
                            conversationMode = ConversationMode.SOLVE,
                            newConversationMode = ConversationMode.SOLVE.takeUnless { old },
                            pageMotion = paperConversationPageMotion()) { Box(Modifier.fillMaxSize()) }
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun begin(route: String, direction: Int, fraction: Float) {
        compose.runOnIdle {
            assertTrue(swipe.start(checkNotNull(nav.currentBackStackEntry),
                PaperSwipeTarget(route, nav.previousBackStackEntry?.takeIf { it.destination.route == route }),
                direction, view.width.toFloat()))
            swipe.drag(view.width * fraction * direction)
        }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
    }

    private fun release() {
        compose.runOnIdle { swipe.release(0f, cancelled = false) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun header(name: String): Bitmap {
        lateinit var result: Bitmap
        compose.waitForIdle()
        // Recording an offscreen entry can invalidate nested text layers. Flush
        // that draw before sampling the overlay rather than reading its old list.
        compose.runOnIdle {
            val frame = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try { view.draw(Canvas(frame)) } finally { frame.recycle() }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                // Stay inside the front paper, away from its changing underlay/shadow.
                result = Bitmap.createBitmap(bitmap, 0, (12 * density).toInt(), view.width, (36 * density).toInt())
                if (System.getenv("TITLE_NAV_EXPORT") == "1") {
                    val file = File("build/ui-preview/title-navigation-$name.png")
                    file.parentFile?.mkdirs()
                    file.outputStream().use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } finally { bitmap.recycle() }
        }
        return result
    }
}
