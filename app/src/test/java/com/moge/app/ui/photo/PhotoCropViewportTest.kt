package com.moge.app.ui.photo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w1280dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoCropViewportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `wide dialog centers the crop area before and after rotating the photo`() {
        val file = File.createTempFile("wide-crop-", ".jpg")
        val photo = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
        try {
            photo.eraseColor(android.graphics.Color.WHITE)
            file.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            compose.setContent { MogeTheme { PhotoCropDialog(file.absolutePath, {}, {}) } }
            fun assertCentered() {
                val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
                val area = compose.onNodeWithTag("photo-crop-area").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals("A width-capped viewport must remain centered in the dialog", dialog.center.x, area.center.x, 1f)
                compose.onNodeWithText("确定").assertIsDisplayed().assertIsEnabled()
                compose.onNodeWithText("取消").assertIsDisplayed()
            }
            assertCentered()
            compose.onNodeWithText("逆时针 90°").performClick()
            assertCentered()
        } finally { photo.recycle(); file.delete() }
    }

    @Test fun `paper handles remain visible and draggable beyond all four edges of a white photo`() =
        verifyHandles(Appearance.PAPER, android.graphics.Color.WHITE)

    @Test fun `chalk handles remain visible and draggable beyond all four edges of a dark photo`() =
        verifyHandles(Appearance.CHALK, android.graphics.Color.BLACK)

    private fun verifyHandles(appearance: Appearance, background: Int) {
        val photo = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(background) }
        lateinit var editor: CropViewportState
        lateinit var view: View
        var accent = 0
        compose.setContent { MogeTheme(appearance) {
            view = LocalView.current
            accent = MaterialTheme.colorScheme.primary.toArgb()
            val scope = rememberCoroutineScope()
            editor = remember { CropViewportState(scope).apply { reset(androidx.compose.ui.unit.IntSize(400, 400)) } }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PhotoCropViewport(photo, editor, saving = false, motionEnabled = false, modifier = Modifier.size(320.dp))
            }
        } }
        val area = compose.onNodeWithTag("photo-crop-area")
        val outer = area.fetchSemanticsNode().boundsInRoot
        val inner = compose.onNodeWithTag("photo-crop-viewport").fetchSemanticsNode().boundsInRoot
        val density = view.resources.displayMetrics.density
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        try {
            // Sample the opaque handle center outside the image, away from antialiased circle edges.
            val step = 2f * density
            val exposed = listOf(inner.topLeft + Offset(-step, -step), inner.topRight + Offset(step, -step),
                inner.bottomLeft + Offset(-step, step), inner.bottomRight + Offset(step, step))
            for (point in exposed) {
                assertTrue("The complete handle must fit outside the photo clip", outer.contains(point))
                assertEquals("Theme-colored handles must draw over the photo edge", accent,
                    bitmap.getPixel(point.x.toInt(), point.y.toInt()))
            }
            val sample = File("build/ui-refinement-previews/crop-${appearance.name.lowercase()}.png")
            sample.parentFile?.mkdirs()
            sample.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            area.performTouchInput {
                down(inner.topLeft - outer.topLeft - Offset(step, step))
                moveBy(Offset(28f * density, 36f * density), delayMillis = 160)
                up()
            }
            compose.runOnIdle {
                val crop = cropBoundsInSource(androidx.compose.ui.unit.IntSize(400, 400),
                    androidx.compose.ui.unit.IntSize(400, 400), editor.viewport, editor.zoom, editor.imageOffset, editor.cropRect)
                assertTrue("The outside handle must resize the selection in photo coordinates", crop.left > 0 && crop.top > 0)
                assertEquals(400f, (crop.left + crop.width).toFloat(), 1f)
                assertEquals(400f, (crop.top + crop.height).toFloat(), 1f)
            }
        } finally { bitmap.recycle(); photo.recycle() }
    }
}
