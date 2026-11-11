package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import io.noties.markwon.Markwon
import io.noties.markwon.core.spans.OrderedListItemSpan
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownBulletAppearanceTest {
    private val previewSource = """
        ### 列表样式预览

        - **采样与量化**：采样把时间连续的信号变成离散序列，量化再把幅度映射到有限个电平。
        - **编码与传输**：每个采样点按约定位数编码，输出速率由采样频率和编码位数共同决定。
          - 子项目沿用相同的空心圆，缩进继续保留。
          - 字号变化时，圆环和描边会随正文一起调整。
        - **检查结果**：长段落会在列表缩进内正常换行，圆环只标记首行，不遮挡后续内容。

        1. 先确认采样频率与量化位数。
        2. 再检查输出速率和传输带宽是否匹配。
    """.trimIndent()

    @Test fun bulletIsAnOpenThemeColoredOutlineAtBothTextSizes() {
        val context = RuntimeEnvironment.getApplication()
        for ((themeName, background, ink, marker) in listOf(
            PreviewTheme("paper", Color.rgb(247, 245, 239), Color.rgb(26, 27, 30), Color.rgb(113, 134, 126)),
            PreviewTheme("chalk", Color.rgb(36, 51, 45), Color.rgb(236, 237, 230), Color.rgb(157, 185, 174)),
        )) {
            for (size in listOf(28f, 42f)) {
                val view = createMarkdownTextView(context, ink, Color.BLUE,
                    markerColor = marker, selectable = false, fontSizePx = size)
                renderMarkdown(view, "- 46: 1.5 / 2", 400, ink, Color.BLUE)
                view.layoutParams = ViewGroup.LayoutParams(400, ViewGroup.LayoutParams.WRAP_CONTENT)
                view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(image).apply { drawColor(background); view.draw(this) }
                    val theme = (view.tag as Markwon).configuration().theme()
                    val inkBounds = android.graphics.Rect(image.width, image.height, -1, -1)
                    for (y in 0 until view.layout.getLineBottom(0)) {
                        for (x in 0 until theme.blockMargin + view.totalPaddingLeft) {
                            val pixel = image.getPixel(x, y)
                            val isMarker = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                                .zip(listOf(Color.red(marker), Color.green(marker), Color.blue(marker)))
                                .all { (actual, expected) -> abs(actual - expected) <= 100 }
                            if (isMarker) {
                                inkBounds.left = minOf(inkBounds.left, x)
                                inkBounds.top = minOf(inkBounds.top, y)
                                inkBounds.right = maxOf(inkBounds.right, x)
                                inkBounds.bottom = maxOf(inkBounds.bottom, y)
                            }
                        }
                    }
                    assertTrue("$themeName $size must draw a marker", inkBounds.right >= 0)
                    val actualCenterX = (inkBounds.left + inkBounds.right) / 2
                    val actualCenterY = (inkBounds.top + inkBounds.bottom) / 2
                    val radius = (inkBounds.width() + inkBounds.height()) / 4f
                    var outline = 0
                    for (angleDegrees in 0 until 360 step 5) {
                        val angle = Math.toRadians(angleDegrees.toDouble())
                        val x = (actualCenterX + radius * kotlin.math.cos(angle)).roundToInt()
                        val y = (actualCenterY + radius * kotlin.math.sin(angle)).roundToInt()
                        val pixel = image.getPixel(x, y)
                        val matches = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                            .zip(listOf(Color.red(marker), Color.green(marker), Color.blue(marker)))
                            .all { (actual, expected) -> abs(actual - expected) <= 100 }
                        if (matches) outline++
                    }
                    assertTrue("$themeName $size must draw a near-circular theme-colored outline ($outline/72)", outline >= 16)
                    assertEquals("$themeName $size must retain an open interior", background,
                        image.getPixel(actualCenterX, actualCenterY))
                    assertTrue("$themeName $size outline is too small", radius >= 2f)
                    assertEquals(ink, Paint().apply { color = ink }.also(theme::applyListItemStyle).color)
                    val file = File("build/list-marker-samples/$themeName-${size.toInt()}.png")
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    renderMarkdown(view, previewSource, 620, ink, Color.BLUE)
                    view.layoutParams = ViewGroup.LayoutParams(620, ViewGroup.LayoutParams.WRAP_CONTENT)
                    view.measure(View.MeasureSpec.makeMeasureSpec(620, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                    val preview = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(preview).apply { drawColor(background); view.draw(this) }
                        File("build/list-marker-samples/full-$themeName-${size.toInt()}.png").outputStream().use {
                            preview.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { preview.recycle() }
                } finally { image.recycle() }
            }
        }
    }

    @Test fun orderedNumbersKeepBodyColorDuringStreamingAndAfterCompletion() {
        val context = RuntimeEnvironment.getApplication()
        for ((_, background, ink, marker) in listOf(
            PreviewTheme("paper", Color.WHITE, Color.rgb(26, 27, 30), Color.rgb(113, 134, 126)),
            PreviewTheme("chalk", Color.rgb(36, 51, 45), Color.rgb(236, 237, 230), Color.rgb(157, 185, 174)),
        )) {
            for (size in listOf(28f, 42f)) {
                val view = createMarkdownTextView(context, ink, ink,
                    markerColor = marker, selectable = false, fontSizePx = size, incrementalUpdates = true)
                view.layoutParams = ViewGroup.LayoutParams(620, ViewGroup.LayoutParams.WRAP_CONTENT)
                val tokenizer = StreamingMarkdownTokenizer()
                val source = "26. A 27. A 28. B 29. D 30. C\n\n1. First step\n2. Second step"
                for (flush in listOf(false, true)) {
                    // Exercise the stream renderer's tail and the full completed document.
                    if (!flush) {
                        val partial = "26. A 27. A 28. B"
                        renderStreamingMarkdown(view, tokenizer.update(partial), 620, ink, ink)
                    } else {
                        renderMarkdown(view, source, 620, ink, ink)
                    }
                    view.measure(View.MeasureSpec.makeMeasureSpec(620, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                    val spans = view.text as Spanned
                    assertTrue(spans.getSpans(0, spans.length, OrderedListItemSpan::class.java).isNotEmpty())
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(bitmap).apply { drawColor(background); view.draw(this) }
                        var numberPixels = 0
                        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                            val pixel = bitmap.getPixel(x, y)
                            val isAccent = abs(Color.red(pixel) - Color.red(marker)) <= 3 &&
                                abs(Color.green(pixel) - Color.green(marker)) <= 3 &&
                                abs(Color.blue(pixel) - Color.blue(marker)) <= 3
                            assertTrue("Ordered numbers must not use bullet color, size=$size flush=$flush", !isAccent)
                            if (y < view.layout.getLineBottom(0) &&
                                x < view.totalPaddingLeft + view.layout.getPrimaryHorizontal(0) && pixel == ink) numberPixels++
                        }
                        assertTrue("The ordered marker must actually be drawn in body ink", numberPixels > 8)
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    private data class PreviewTheme(val name: String, val background: Int, val ink: Int, val marker: Int)
}
