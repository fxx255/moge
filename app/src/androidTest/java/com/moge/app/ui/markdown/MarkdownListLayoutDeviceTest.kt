package com.moge.app.ui.markdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Selection
import android.text.Spannable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.prefs.AppFont
import com.moge.app.ui.theme.appTypeface
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android's actual DynamicLayout, including indentation added after text already exists. */
@RunWith(AndroidJUnit4::class)
class MarkdownListLayoutDeviceTest {
    @Test fun growingReviewKeepsEveryLineAndTheSameLayoutOnCompletion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = """
            1. `Dear teacher,`

               能看懂，但英语邮件中这样称呼不自然，最好写具体身份，如：

               > Dear Professor Smith,

            2. `It is your engagement makes us feel honored.`

               即使按强调句理解，也缺少 `that`，而且 `engagement` 在这里不如 `participation` 自然。可改为：

               > We would be honored if you could accept our invitation.

            3. `Looking forward to your response.`

               作为邮件结尾可以看懂，但完整句式更稳妥：

               > I look forward to your response.
        """.trimIndent()
        instrumentation.runOnMainSync {
            for ((name, ink, background) in listOf(Triple("chalk", Color.WHITE, Color.rgb(30, 43, 38)),
                Triple("paper", Color.BLACK, Color.rgb(248, 246, 239)))) {
                val width = if (name == "chalk") 620 else 928
                val current = createMarkdownTextView(context, ink, ink, fontSizePx = 42f,
                    bodyTypeface = appTypeface(context, AppFont.WENKAI), incrementalUpdates = true).apply {
                    setSpannableFactory(StreamingTextBufferFactory)
                    layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                val reference = createMarkdownTextView(context, ink, ink, fontSizePx = 42f,
                    bodyTypeface = appTypeface(context, AppFont.WENKAI)).apply {
                    layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                val tokenizer = StreamingMarkdownTokenizer()
                var buffer: CharSequence? = null
                var layout: android.text.Layout? = null
                val initial = source.indexOf("能看懂") + 1
                for (end in (initial until source.length step 17).toList() + source.length) {
                    val partial = source.take(end)
                    val snapshot = tokenizer.update(partial).copy(blocks = emptyList(), tail = partial, tailStart = 0,
                        visibleEnd = partial.length)
                    for (view in listOf(reference, current)) {
                        renderStreamingMarkdown(view, snapshot, width, ink, ink)
                        measure(view, width)
                    }
                    if (buffer == null) { buffer = current.text; layout = current.layout }
                    assertSame(buffer, current.text)
                    assertSame(layout, current.layout)
                    assertSameLines(reference, current)
                }
                Selection.setSelection(current.text as Spannable, 0, 4)
                val completed = tokenizer.update(source, flush = true).copy(blocks = emptyList(), tail = source,
                    tailStart = 0, renderedPrefixEnd = source.length, visibleEnd = source.length)
                for (view in listOf(reference, current)) {
                    renderStreamingMarkdown(view, completed, width, ink, ink)
                    measure(view, width)
                }
                assertSame(buffer, current.text)
                assertSame(layout, current.layout)
                assertEquals(0, current.selectionStart)
                assertEquals(4, current.selectionEnd)
                Selection.setSelection(reference.text as Spannable, 0, 4)
                assertSameLines(reference, current)
                assertEquals(current.text.toString(), selectedMarkdownText(current.text, 0, current.text.length))
                val actual = draw(current, background)
                val expected = draw(reference, background)
                try {
                    assertTrue("Streaming ink must match full rendering in $name", actual.sameAs(expected))
                    File(context.getExternalFilesDir(null), "list-layout-$name.png").outputStream().use {
                        actual.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { actual.recycle(); expected.recycle() }
            }
        }
    }

    private fun assertSameLines(reference: TextView, current: TextView) {
        assertEquals(reference.text.toString(), current.text.toString())
        assertEquals(reference.height, current.height)
        assertEquals(reference.layout.lineCount, current.layout.lineCount)
        for (line in 0 until current.layout.lineCount) {
            assertEquals(reference.layout.getLineStart(line), current.layout.getLineStart(line))
            assertEquals(reference.layout.getLineBottom(line), current.layout.getLineBottom(line))
            assertTrue("Line $line exceeds the right edge", current.layout.getLineRight(line) <= current.layout.width + 1)
        }
    }

    private fun draw(view: TextView, background: Int) =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(background); view.draw(this) }
        }

    private fun measure(view: TextView, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
}
