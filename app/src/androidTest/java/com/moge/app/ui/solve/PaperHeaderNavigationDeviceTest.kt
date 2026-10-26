package com.moge.app.ui.solve

import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import com.moge.app.ui.PaperNavHost
import com.moge.app.ui.PaperSwipeDestination
import com.moge.app.ui.PaperSwipeTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.createGraph
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.HistoryEntry
import com.moge.app.data.prefs.Appearance
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.components.*
import com.moge.app.ui.history.HistoryContent
import com.moge.app.ui.history.HistoryUiState
import com.moge.app.ui.notebook.NotebookContent
import com.moge.app.ui.notebook.NotebookUiState
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Sample real navigation frames rather than only checking the final composed tree. */
@RunWith(AndroidJUnit4::class)
class PaperHeaderNavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun headersStayOpaqueAndFiltersClearThePaperInBothThemes() {
        lateinit var nav: NavHostController
        var appearance by mutableStateOf(Appearance.PAPER)
        var front = 0
        val ribbon = ConversationRibbonState()
        compose.setContent { MogeTheme(appearance) {
            front = MogeTheme.paper.headerPaper.toArgb()
            nav = rememberNavController()
            PaperNavigationLayers {
                val graph = remember(nav) { nav.createGraph(startDestination = "conversation") {
                    listOf("conversation", "old", "history", "notebook", "settings").forEach { composable(it) { } }
                } }
                PaperNavHost(nav, graph, null, hasComposer = {
                    it.destination.route == "conversation" || it.destination.route == "old"
                }) { entry ->
                    val name = entry.destination.route!!
                    val left = when(name) { "history" -> "conversation"; "conversation" -> "old"; "old" -> "notebook"; else -> null }
                    val right = when(name) { "conversation" -> "history"; "old" -> "conversation"; "notebook" -> "old"; else -> null }
                    PaperSwipeDestination(entry, target = { direction ->
                        (if (direction > 0) right else left)?.let { route ->
                            PaperSwipeTarget(route, nav.previousBackStackEntry?.takeIf { it.destination.route == route })
                        }
                    }) {
                    when (name) {
                    "conversation", "old" -> {
                        PageSwipeSurface(onLeft = left?.let { { nav.navigate(it) } },
                            onRight = right?.let { { nav.navigate(it) } }, modifier = Modifier.testTag("drag-$name")) {
                            val motion = paperConversationPageMotion()
                            ConversationScaffold("满秩分解求矩阵的高次幂与线性相关关系", null, {}, footer = {
                                FollowUpBar(SolveUiState(input = "尚未发送的草稿"), {}, {}, {}, {}, {}, {}, {},
                                    switchingPage = motion.switching, swipeDirection = motion.direction,
                                    swipeProgress = motion.progress)
                            },
                                pageMotion = motion, ribbonState = ribbon,
                                newConversationMode = ConversationMode.SOLVE.takeIf { name == "conversation" }) {
                                Box(Modifier.fillMaxSize().background(Color.Red))
                            }
                        }
                    }
                    "history" -> {
                            HistoryContent(HistoryUiState(loading = false, entries = listOf(
                                HistoryEntry(ConversationEntity(id = "example", title = "矩阵计算"), "求解矩阵方程"),
                            )), onBack = {}, onOpenConversation = {}, onQuery = {}, onToggleSelection = {},
                                onClearSelection = {}, onSelectAll = {}, onRename = {}, onDelete = {},
                                onPin = {}, onRetry = {}, onDismissMessage = {}, onNewConversation = { nav.navigate("conversation") })
                    }
                    "notebook" -> {
                            NotebookContent(NotebookUiState(loading = false), onBack = {},
                                onOpenConversation = {}, onQuery = {}, onCategory = { _, _ -> },
                                onToggleSelection = {}, onClearSelection = {}, onSelectAll = {},
                                onOpenEntry = {}, onCloseEntry = {}, onMove = {}, onDelete = {},
                                onCreateCategory = {}, onRenameCategory = { _, _ -> },
                                onReorderCategory = { _, _ -> }, onDeleteCategory = {}, onRetry = {},
                                onDismissMessage = {}, onReturnToConversation = { nav.navigate("old") })
                    }
                    "settings" -> {
                            PaperScaffold("设置", {}, headerStyle = PaperHeaderStyle.Single) {
                                Text("语言与字体", color = MaterialTheme.colorScheme.onBackground)
                            }
                    }
                    }
                    }
                }
            }
        } }
        compose.waitForIdle()
        val initial = compose.onNodeWithTag("paper-strip-header").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        // Moving long titles cross the left edge. Sample the blank upper band,
        // where glyph antialiasing cannot be mistaken for transparent paper.
        val paperSampleY = (initial.top + (PaperHeaderTopGap.value + 2f) * density).toInt()
        listOf(Appearance.PAPER, Appearance.CHALK).forEach { theme ->
            compose.runOnIdle { appearance = theme }
            // Slow actual drags through all four horizontally adjacent pages.
            listOf("history", "conversation", "old", "notebook", "old", "conversation").forEachIndexed { index, destination ->
                val source = nav.currentDestination?.route!!
                val direction = if ((source == "conversation" && destination == "history") ||
                    (source == "notebook" && destination == "old") || (source == "old" && destination == "conversation")) 1 else -1
                val sourceHasInput = source == "conversation" || source == "old"
                val targetHasInput = destination == "conversation" || destination == "old"
                val restTop = if (sourceHasInput) compose.onNodeWithTag("composer-surface")
                    .fetchSemanticsNode().positionInRoot.y else null
                compose.onRoot().performTouchInput {
                    down(Offset(if (direction > 0) width * .2f else width * .8f, height * .55f))
                    moveBy(Offset(width * .5f * direction, 0f), delayMillis = 350)
                }
                compose.waitForIdle()
                val halfTop = compose.onNodeWithTag("composer-surface").fetchSemanticsNode().positionInRoot.y
                if (restTop != null) {
                    if (targetHasInput) assertEquals("Conversation swaps keep the input level", restTop, halfTop, .1f)
                    else assertTrue("The input lowers while dragging to a list", halfTop > restTop + 1f)
                }
                val half = compose.onRoot().captureToImage().toPixelMap()
                if (front != half[2, paperSampleY].toArgb()) {
                    snapshot("header-opacity-$source-$destination-${theme.name.lowercase()}.png")
                }
                assertEquals("Shared paper stays opaque during $source → $destination ($theme, y=$paperSampleY)",
                    front, half[2, paperSampleY].toArgb())
                if (source == "conversation" && destination == "old") {
                    compose.mainClock.advanceTimeBy(800)
                    val held = compose.onRoot().captureToImage().toPixelMap()
                    for (y in (initial.top + 10 * density).toInt() until (initial.bottom - 10 * density).toInt()) {
                        for (x in 0 until half.width) {
                            assertEquals("Incoming old title must not keep writing or scrolling ($theme, $x, $y)",
                                half[x, y], held[x, y])
                        }
                    }
                    snapshot("old-title-held-${theme.name.lowercase()}.png")
                }
                if (sourceHasInput && !targetHasInput && restTop != null) {
                    val sourceX = (half.width * if (direction > 0) .75f else .25f).toInt()
                    assertEquals("Input ink clears its old location while the finger is held", Color.Red.toArgb(),
                        half[sourceX, (restTop + 12 * density).toInt()].toArgb())
                }
                if (index == 0 || index == 3) snapshot("drag-half-$source-${theme.name.lowercase()}.png")
                compose.onRoot().performTouchInput { advanceEventTime(150); up() }
                compose.waitForIdle()
                compose.runOnIdle { assertEquals(destination, nav.currentDestination?.route) }
                if (targetHasInput && !sourceHasInput) {
                    assertTrue("Returning from a list raises the input", compose.onNodeWithTag("composer-surface")
                        .fetchSemanticsNode().positionInRoot.y < halfTop - 1f)
                } else if (!targetHasInput) compose.onNodeWithTag("composer-surface").assertDoesNotExist()
            }
            repeat(2) { cycle ->
                listOf("history", "notebook", "settings", "conversation").forEach { page ->
                    compose.mainClock.autoAdvance = false
                    compose.runOnIdle { nav.navigate(page) }
                    compose.mainClock.advanceTimeBy(32)
                    repeat(4) { frame ->
                        compose.onAllNodesWithTag("paper-strip-header").fetchSemanticsNodes().forEach {
                            assertEquals("Paper stays fixed horizontally", initial.left, it.boundsInRoot.left, .1f)
                            assertEquals(initial.width, it.boundsInRoot.width, .1f)
                        }
                        val pixels = compose.onRoot().captureToImage().toPixelMap()
                        assertEquals("No transparent header frame on $page", front, pixels[2, paperSampleY].toArgb())
                        if (cycle == 0 && frame == 1) snapshot("header-$page-${theme.name.lowercase()}-transition.png")
                        compose.mainClock.advanceTimeBy(48)
                    }
                    compose.mainClock.advanceTimeBy(600)
                    compose.mainClock.autoAdvance = true
                    compose.waitForIdle()
                    if (page == "history" || page == "notebook") {
                        val header = compose.onNodeWithTag("paper-strip-header").fetchSemanticsNode().boundsInRoot
                        val search = compose.onNodeWithTag("paper-search-panel").fetchSemanticsNode().boundsInRoot
                        assertEquals("The category gap is halved", 10 * density, search.top - header.bottom, 1f)
                    }
                    if (cycle == 0) snapshot("header-$page-${theme.name.lowercase()}.png")
                }
            }
        }
    }

    private fun snapshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try {
                File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { bitmap.recycle() }
        }
    }
}
