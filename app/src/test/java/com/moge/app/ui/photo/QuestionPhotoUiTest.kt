package com.moge.app.ui.photo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuestionPhotoUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    @After fun clear() = ThumbnailCache.clear()

    @Test fun `long question displays its complete aspect ratio and both ends remain in decoded image`() {
        val path = temporary.newFile().also { file ->
            val bitmap = Bitmap.createBitmap(400, 1600, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            canvas.drawRect(0f, 0f, 400f, 80f, Paint().apply { color = Color.RED })
            canvas.drawRect(0f, 1520f, 400f, 1600f, Paint().apply { color = Color.BLUE })
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }.path
        var opened = 0
        compose.setContent { MogeTheme { QuestionPhoto(path, "完整题目", { opened++ }, Modifier.width(100.dp)) } }
        compose.waitUntil(5_000) { ThumbnailCache.peek(path, 4096) != null }
        val image = compose.onNodeWithContentDescription("完整题目")
        val bounds = image.fetchSemanticsNode().boundsInRoot
        assertEquals(4f, bounds.height / bounds.width, .02f)
        val decoded = ThumbnailCache.peek(path, 4096)!!
        assertEquals(Color.RED, decoded.getPixel(200, 20))
        assertEquals(Color.BLUE, decoded.getPixel(200, 1580))
        image.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
