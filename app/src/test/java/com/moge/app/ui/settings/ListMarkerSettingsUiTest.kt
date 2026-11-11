package com.moge.app.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.moge.app.data.prefs.*
import com.moge.app.ui.components.PaperScaffold
import com.moge.app.ui.markdown.MarkdownChunk
import com.moge.app.ui.markdown.StreamingMarkdownChunk
import com.moge.app.ui.markdown.StreamingMarkdownTokenizer
import com.moge.app.ui.theme.MogeTheme
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w480dp-h1100dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListMarkerSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun chooseEveryStyleWithInlinePreviewAndFoldedAdjustments() {
        var settings by mutableStateOf(ListMarkerSettings())
        var mounts = 0
        compose.setContent {
            MogeTheme(appearance = Appearance.PAPER, font = AppFont.WENKAI, listMarkers = settings) {
                DisposableEffect(Unit) { mounts++; onDispose { mounts-- } }
                PaperScaffold(title = "设置", onBack = {}) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        ListMarkerSettingsSection(settings) { settings = it }
                        MarkdownChunk("- 正文同步更新。", null, selectable = false)
                        StreamingMarkdownChunk(remember { StreamingMarkdownTokenizer().update("- 流式同步更新。") })
                    }
                }
            }
        }
        fun ink(phrase: String = "正文同步"): List<Int> {
            compose.waitForIdle()
            val view = native(compose.activity.window.decorView).first { it.text.contains(phrase) }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                return (0 until bitmap.height).flatMap { y -> (0 until bitmap.width).map { x -> bitmap.getPixel(x, y) } }
            } finally { bitmap.recycle() }
        }
        assertAdjustmentsCollapsed()
        compose.onNodeWithTag("list-marker-selected-swatch", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("list-marker-preview").assertDoesNotExist()
        compose.runOnIdle { assertTrue(native(compose.activity.window.decorView).none { it.text.contains("提出问题") }) }
        val original = ink()
        val originalStream = ink("流式同步")
        val originalMarker = markerInk()
        screenshot("settings-paper")
        compose.onNodeWithTag("list-marker-adjustments-toggle").performClick()
        for (style in ListMarkerStyle.entries) {
            compose.onNodeWithTag("list-marker-style-selector").performScrollTo().performClick()
            compose.onNodeWithTag("list-marker-option-${style.name}").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(style, settings.style); assertEquals(1, mounts) }
            if (style.hasStroke) compose.onNodeWithTag("list-marker-stroke").assertExists()
            else compose.onNodeWithTag("list-marker-stroke").assertDoesNotExist()
        }
        assertNotEquals(original, ink())
        assertNotEquals(originalStream, ink("流式同步"))
        assertNotEquals(originalMarker, markerInk())
        compose.onNodeWithTag("list-marker-size").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(150f) }
        compose.runOnIdle { assertEquals(150, settings.sizePercent) }
        assertNotEquals(original, ink())
        val adjustedMarker = markerInk()
        compose.onNodeWithTag("list-marker-adjustments-toggle").performScrollTo().performClick()
        assertAdjustmentsCollapsed()
        compose.runOnIdle { assertEquals(150, settings.sizePercent) }
        assertEquals(adjustedMarker, markerInk())
        compose.onNodeWithTag("list-marker-adjustments-toggle").performClick()
        compose.onNodeWithTag("list-marker-reset").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ListMarkerSettings(), settings) }
        assertEquals(original, ink())
        assertEquals(originalStream, ink("流式同步"))
        assertEquals(originalMarker, markerInk())
    }

    @Test fun enlargedChalkPreviewRemainsScrollableAndUsesTheSelectedLanguage() {
        var settings by mutableStateOf(ListMarkerSettings())
        compose.setContent {
            MogeTheme(appearance = Appearance.CHALK, language = AppLanguage.ENGLISH,
                bodyTextSizeSp = 24, uiScalePercent = 150, listMarkers = settings) {
                PaperScaffold(title = "Settings", onBack = {}) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        ListMarkerSettingsSection(settings) { settings = it }
                    }
                }
            }
        }
        compose.onNodeWithText("List markers").assertExists()
        assertAdjustmentsCollapsed()
        compose.onNodeWithTag("list-marker-style-selector").performScrollTo().performClick()
        compose.onNodeWithTag("list-marker-option-RING").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ListMarkerStyle.RING, settings.style) }
        compose.onNodeWithTag("list-marker-selected-swatch", useUnmergedTree = true).assertIsDisplayed()
        compose.waitForIdle()
        screenshot("settings-chalk-large")
        compose.onNodeWithTag("list-marker-adjustments-toggle").performClick()
        compose.onNodeWithTag("list-marker-reset").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("list-marker-adjustments-toggle").performScrollTo().performClick()
        assertAdjustmentsCollapsed()
    }

    private fun assertAdjustmentsCollapsed() {
        for (tag in listOf("list-marker-size", "list-marker-stroke", "list-marker-indent", "list-marker-reset")) {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
    }

    private fun markerInk(): List<Int> {
        compose.onNodeWithTag("list-marker-style-selector").performScrollTo()
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("list-marker-selected-swatch", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            return (bounds.top.roundToInt() until bounds.bottom.roundToInt()).flatMap { y ->
                (bounds.left.roundToInt() until bounds.right.roundToInt()).map { x -> bitmap.getPixel(x, y) }
            }
        } finally { bitmap.recycle() }
    }

    private fun screenshot(name: String) {
        val root = compose.activity.window.decorView
        val toggleBounds = compose.onNodeWithTag("list-marker-adjustments-toggle").fetchSemanticsNode().boundsInRoot
        val image = Bitmap.createBitmap(root.width, (toggleBounds.bottom + 20).roundToInt().coerceAtMost(root.height), Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(image))
            val file = File("build/list-marker-samples/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }

    private fun native(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { native(view.getChildAt(it)) }
        else -> emptyList()
    }
}
