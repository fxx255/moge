package com.moge.app.ui.share

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.moge.app.data.prefs.Appearance
import com.moge.app.data.image.decodePhotoThumbnail
import com.moge.app.domain.ConversationMode
import com.moge.app.runtime.SharedImages
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w440dp-h650dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedImageAttachmentPanelTest {
    @get:Rule val compose = createComposeRule()
    private var sampleDecoder = false

    @After fun restoreSampleDecoder() {
        if (sampleDecoder) unmockkStatic("com.moge.app.data.image.PhotoThumbnailDecoderKt")
    }

    @Test fun historicalDestinationsStartCollapsedKeepTheSelectionAndDisableWhileBusy() {
        val restore = StateRestorationTester(compose)
        val busy = mutableStateOf(false)
        val chosen = mutableListOf<String>()
        val history = listOf(
            SharedConversationTarget("new", "最新的对话", ConversationMode.CHAT, java.time.Instant.ofEpochSecond(30)),
            SharedConversationTarget("old", "以前的题目", ConversationMode.SOLVE, java.time.Instant.ofEpochSecond(10)),
        )
        restore.setContent { MogeTheme {
            SharedImageAttachmentPanel(SharedImages("history", emptyList()), null, null, false, busy.value, null,
                {}, {}, Modifier.width(360.dp).heightIn(max = 480.dp),
                history = history, onConfirmHistory = { chosen += it.id })
        } }
        compose.onNodeWithTag("share-history-menu").assertDoesNotExist()
        compose.onNodeWithTag("share-join-current").assertIsNotEnabled()
        val card = compose.onNodeWithTag("share-current-card").fetchSemanticsNode().boundsInRoot
        val toggle = compose.onNodeWithTag("share-history-toggle").assertIsEnabled()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        val arrow = compose.onNodeWithTag("share-current-forward", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(toggle.left >= card.left && toggle.right <= card.right && toggle.top >= card.top && toggle.bottom <= card.bottom)
        assertTrue("The dropdown stays inside the card, to the right of the forward arrow", arrow.right <= toggle.left)
        compose.onNodeWithTag("share-history-toggle").performClick()
        assertTrue(chosen.isEmpty())
        val newest = compose.onNodeWithTag("share-history-new").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val oldest = compose.onNodeWithTag("share-history-old").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(newest.top < oldest.top)
        compose.onNodeWithTag("share-history-old").performClick()
        compose.onNodeWithTag("share-history-menu").assertDoesNotExist()
        compose.onNodeWithText("以前的题目").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("share-join-current").assertIsEnabled().performClick()
        assertEquals(listOf("old"), chosen)
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithTag("share-history-toggle").assertIsNotEnabled()
        compose.onNodeWithTag("share-join-current").assertIsNotEnabled()
    }

    @Test fun threeButtonsDeliverTheExactDestinationInOneTapAndRemainAvailableAfterRestoration() {
        val restore = StateRestorationTester(compose)
        val delivered = mutableListOf<ConversationMode?>()
        restore.setContent { MogeTheme(appearance = Appearance.PAPER) {
            SharedImageAttachmentPanel(SharedImages("batch", listOf("/missing/one.png")),
                ConversationMode.SOLVE, "原来的题目", true, false, null,
                onConfirm = { delivered += it }, onDismiss = {},
                modifier = Modifier.width(360.dp).heightIn(max = 480.dp))
        } }
        compose.onNodeWithText("加入当前对话").assertIsDisplayed().performClick()
        compose.onNodeWithText("加入新题目对话").assertIsDisplayed().performClick()
        compose.onNodeWithText("加入新对话").assertIsDisplayed().performClick()
        assertEquals(listOf(null, ConversationMode.SOLVE, ConversationMode.CHAT), delivered)
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("share-join-new-chat").performClick()
        assertEquals(ConversationMode.CHAT, delivered.last())
        compose.onNodeWithTag("share-destination-picker").assertDoesNotExist()
        compose.onNodeWithTag("share-add-to-input").assertDoesNotExist()
    }

    @Test fun absentOrUnavailableCurrentConversationIsDisabledAndBusyDisablesAllActions() {
        val current = mutableStateOf<ConversationMode?>(null)
        val available = mutableStateOf(false)
        val busy = mutableStateOf(false)
        compose.setContent { MogeTheme {
            SharedImageAttachmentPanel(SharedImages("batch", emptyList()), current.value, null,
                available.value, busy.value, null, {}, {}, Modifier.width(360.dp).heightIn(max = 480.dp))
        } }
        compose.onNodeWithTag("share-join-current").assertIsNotEnabled()
        compose.onNodeWithTag("share-join-new-question").assertIsEnabled()
        compose.onNodeWithTag("share-join-new-chat").assertIsEnabled()
        compose.runOnIdle { current.value = ConversationMode.CHAT }
        compose.onNodeWithTag("share-join-current").assertIsNotEnabled()
        compose.runOnIdle { available.value = true }
        compose.onNodeWithTag("share-join-current").assertIsEnabled()
        compose.runOnIdle { busy.value = true }
        listOf("current", "new-question", "new-chat").forEach {
            compose.onNodeWithTag("share-join-$it").assertIsNotEnabled()
        }
    }

    @Test fun narrowLargeTextKeepsAllThreeActionsReachable() {
        var delivered: ConversationMode? = null
        compose.setContent { MogeTheme(appearance = Appearance.CHALK) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                SharedImageAttachmentPanel(SharedImages("large", List(9) { "/missing/$it.png" }),
                    null, null, false, false, "磁盘空间不足，请清理后重试", { delivered = it }, {},
                    Modifier.width(320.dp).heightIn(max = 270.dp))
            }
        } }
        listOf("current", "new-question", "new-chat").forEach {
            compose.onNodeWithTag("share-join-$it").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("share-join-new-chat").performClick()
        assertEquals(ConversationMode.CHAT, delivered)
    }

    @Test
    @Config(qualifiers = "w720dp-h400dp-xhdpi")
    fun shortLandscapeShowsTheThreeActionsSideBySide() {
        compose.setContent { MogeTheme {
            SharedImageAttachmentPanel(SharedImages("wide", List(9) { "/missing/$it.png" }),
                ConversationMode.CHAT, "已有对话", true, false, null, {}, {},
                Modifier.width(600.dp).heightIn(max = 220.dp))
        } }
        val bounds = listOf("current", "new-question", "new-chat").map {
            compose.onNodeWithTag("share-join-$it").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                .fetchSemanticsNode().boundsInRoot
        }
        assertEquals(bounds[0].top, bounds[1].top, 1f)
        assertEquals(bounds[1].top, bounds[2].top, 1f)
        assertTrue(bounds[0].right < bounds[1].left && bounds[1].right < bounds[2].left)
    }

    @Test fun renderPaperAndChalkStyleSamples() {
        if (System.getenv("SHARE_STYLE_SAMPLES") != "1") return
        val context = RuntimeEnvironment.getApplication()
        val photos = (1..3).map { index ->
            File(context.cacheDir, "share-style-$index.png").also { file ->
                val bitmap = Bitmap.createBitmap(160, 230, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(0xFFFAF5E9.toInt())
                    val canvas = Canvas(bitmap)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF343C40.toInt(); textSize = 16f }
                    canvas.drawText("$index.  f(x) = x² + 1", 12f, 30f, paint)
                    paint.textSize = 12f
                    canvas.drawText("f'(x) = 2x", 16f, 63f, paint)
                    canvas.drawText("x = 1, f(x) = 2", 16f, 88f, paint)
                    paint.color = 0xFFAAA595.toInt(); paint.strokeWidth = 1f
                    for (y in 110..215 step 20) canvas.drawLine(12f, y.toFloat(), 148f, y.toFloat(), paint)
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
        }
        val appearance = mutableStateOf(Appearance.PAPER)
        // Robolectric's region decoder does not draw native crop pixels; use full sample decoding.
        mockkStatic("com.moge.app.data.image.PhotoThumbnailDecoderKt")
        sampleDecoder = true
        every { decodePhotoThumbnail(any(), any(), any()) } answers { BitmapFactory.decodeFile(firstArg<String>()) }
        var view: View? = null
        compose.setContent { MogeTheme(appearance = appearance.value) {
            val currentView = LocalView.current
            SideEffect { view = currentView }
            SharedImageAttachmentPanel(SharedImages("sample", photos.map(File::getAbsolutePath)),
                ConversationMode.SOLVE, "傅里叶级数与周期信号", true, false, null, {}, {},
                Modifier.padding(20.dp).width(360.dp).heightIn(max = 520.dp))
        } }
        for (style in listOf(Appearance.PAPER, Appearance.CHALK)) {
            compose.runOnIdle { appearance.value = style }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("…").fetchSemanticsNodes().isEmpty() }
            val bounds = compose.onNodeWithTag("shared-image-panel").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                val root = requireNotNull(view)
                val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                var panel: Bitmap? = null
                try {
                    root.draw(Canvas(image))
                    panel = Bitmap.createBitmap(image, bounds.left.roundToInt(), bounds.top.roundToInt(),
                        bounds.width.roundToInt(), bounds.height.roundToInt())
                    val output = File("build/share-samples/three-destinations-${style.name.lowercase()}.png")
                    output.parentFile!!.mkdirs()
                    output.outputStream().use { panel.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { panel?.recycle(); image.recycle() }
            }
        }
    }
}
