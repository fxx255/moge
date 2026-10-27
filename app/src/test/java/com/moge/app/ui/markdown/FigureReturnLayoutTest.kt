package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.moge.app.ui.photo.ThumbnailCache
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigureReturnLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun returningPlotKeepsCaptionAndTextInPlaceEvenAfterBitmapEviction() = verifyReturn(false)
    @Test fun returningScrollableDiagramKeepsItsWiderCanvasHeight() = verifyReturn(true)

    private fun verifyReturn(diagram: Boolean) {
        val file = Files.createTempFile(if (diagram) "diagram_return_" else "figure-return-", ".png").toFile()
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        FigureImageCache.clear()
        ThumbnailCache.clear()
        var visible by mutableStateOf(true)
        val loads = AtomicInteger()
        val reloadStarted = CountDownLatch(1)
        val releaseReload = CountDownLatch(1)
        val resolver = FigurePathResolver { path, _ ->
            if (loads.incrementAndGet() > 1) {
                reloadStarted.countDown()
                check(releaseReload.await(10, TimeUnit.SECONDS))
            }
            path
        }
        try {
            compose.setContent { MogeTheme {
                CompositionLocalProvider(LocalFigurePathResolver provides resolver) {
                    Column(Modifier.requiredWidth(300.dp)) {
                        if (visible) StreamingFigureSlot(0, file.absolutePath, false) {}
                        Text("后续正文", Modifier.testTag("following-text"))
                    }
                }
            } }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("图 1").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val caption = compose.onNodeWithText("图 1").fetchSemanticsNode().boundsInRoot
            val following = compose.onNodeWithTag("following-text").fetchSemanticsNode().boundsInRoot
            repeat(3) {
                compose.runOnIdle { visible = false }
                compose.runOnIdle { visible = true }
                compose.onNodeWithContentDescription("图 1").assertExists()
                assertEquals(caption, compose.onNodeWithText("图 1").fetchSemanticsNode().boundsInRoot)
                assertEquals(following, compose.onNodeWithTag("following-text").fetchSemanticsNode().boundsInRoot)
                assertEquals("Returning must reuse the decoded figure", 1, loads.get())
            }
            compose.runOnIdle { visible = false }
            ThumbnailCache.clear()
            compose.runOnIdle { visible = true }
            compose.waitForIdle()
            assertTrue(reloadStarted.await(5, TimeUnit.SECONDS))
            assertEquals("Caption stays put while decoding", caption,
                compose.onNodeWithText("图 1").fetchSemanticsNode().boundsInRoot)
            assertEquals(following, compose.onNodeWithTag("following-text").fetchSemanticsNode().boundsInRoot)
            releaseReload.countDown()
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("图 1").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            assertEquals(caption, compose.onNodeWithText("图 1").fetchSemanticsNode().boundsInRoot)
            assertEquals(following, compose.onNodeWithTag("following-text").fetchSemanticsNode().boundsInRoot)
        } finally {
            releaseReload.countDown()
            file.delete()
            FigureImageCache.clear()
            ThumbnailCache.clear()
        }
    }
}
