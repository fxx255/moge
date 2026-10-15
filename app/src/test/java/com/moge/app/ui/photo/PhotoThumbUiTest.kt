package com.moge.app.ui.photo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.moge.app.data.image.PixelRegionDecoder
import com.moge.app.ui.theme.MogeTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [PixelRegionDecoder::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoThumbUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    @After fun clear() = ThumbnailCache.clear()

    @Test fun `photo thumbnail uses physical display size and reloads another path without resizing`() {
        var path by mutableStateOf(photo(Color.RED))
        val nextPhoto = photo(Color.BLUE)
        compose.setContent { MogeTheme {
            PhotoThumb(path, "题目照片", {}, Modifier.size(160.dp, 120.dp).testTag("photo"))
        } }
        val bounds = compose.onNodeWithTag("photo").fetchSemanticsNode().boundsInRoot
        val size = IntSize(bounds.width.roundToInt(), bounds.height.roundToInt())
        fun awaitPhoto(color: Int) {
            compose.waitUntil(5_000) { ThumbnailCache.peek(path, THUMB_MAX_PX, cropSize = size) != null }
            compose.onNodeWithContentDescription("题目照片").assertExists()
            val bitmap = requireNotNull(ThumbnailCache.peek(path, THUMB_MAX_PX, cropSize = size))
            assertEquals(size.width, bitmap.width)
            assertEquals(size.height, bitmap.height)
            assertEquals(color, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
        }
        awaitPhoto(Color.RED)
        compose.runOnIdle { path = nextPhoto }
        compose.waitForIdle()
        awaitPhoto(Color.BLUE)
    }

    private fun photo(color: Int) = temporary.newFile().also { file ->
        val bitmap = Bitmap.createBitmap(640, 3200, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }.absolutePath
}
