package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.view.View
import android.widget.TextView
import com.moge.app.core.LatexEngine
import com.moge.app.ui.export.exportTextView
import io.noties.markwon.image.AsyncDrawableScheduler
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathDrawable
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntegralFormulaRenderTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val formulas = listOf(
        """I=\oiint_S z\,\mathrm{d}S;""",
        """=\oiint_S z\,\mathrm{d}S.""",
        """I=\iiint_\Omega 3r^2\cdot r^2\sin\varphi\,dr\,d\varphi\,d\theta""",
    )

    @Before fun init() { LatexEngine.init(context) }

    private fun newView() = createMarkdownTextView(context, Color.BLACK, Color.BLUE, fontSizePx = 22f)

    private fun spans(view: TextView): List<BaselineLatexSpan> {
        AsyncDrawableScheduler.unschedule(view)
        val text = view.text as Spanned
        assertFalse(text.toString(), text.contains('$'))
        return text.getSpans(0, text.length, BaselineLatexSpan::class.java).toList()
    }

    private fun nativeBitmap(latex: String, size: Float = 64f): Bitmap {
        val drawable = JLatexMathDrawable.builder(latex).textSize(size).color(Color.BLACK).build()
        assertTrue(drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
        return Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888).apply {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(this))
        }
    }

    private fun loadAndDraw(view: TextView, width: Int) {
        val formulas = spans(view)
        assertTrue("Expected a native formula", formulas.isNotEmpty())
        for (span in formulas) {
            // Build the actual destination, including wrap output and boundary newlines.
            val drawable = JLatexMathDrawable.builder(span.drawable.destination).textSize(view.textSize).build()
            span.drawable.initWithKnownDimensions(width - view.totalPaddingLeft - view.totalPaddingRight, view.textSize)
            span.drawable.setResult(drawable)
        }
        view.text = view.text
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val text = view.text as Spanned
        for (span in formulas) {
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            val line = view.layout.getLineForOffset(start)
            val baseline = view.totalPaddingTop + view.layout.getLineBaseline(line)
            val top = baseline - span.baselineOffset(view.paint)
            assertTrue("Integral top clipped", top >= 0)
            assertTrue("Integral bottom clipped", top + span.drawable.bounds.height() <= view.height)
            assertTrue("Integral right clipped: ${span.drawable.destination} (${span.drawable.bounds.width()} / $width)",
                view.totalPaddingLeft + view.layout.getPrimaryHorizontal(start) +
                span.drawable.bounds.width() <= width)
            val image = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
            try {
                span.draw(Canvas(image), text, start, end,
                    view.totalPaddingLeft + view.layout.getPrimaryHorizontal(start),
                    view.layout.getLineTop(line), baseline, view.layout.getLineBottom(line), view.paint)
                val pixels = IntArray(image.width * image.height)
                image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                assertTrue("Integral must paint actual pixels", pixels.any { it ushr 24 > 0 })
            } finally { image.recycle() }
        }
    }

    @Test
    fun `exact user formulas render inline and as blocks at phone and tablet widths`() {
        for (formula in formulas) for (width in listOf(320, 900)) {
            for (source in listOf("$$$formula$$", "\\[\n$formula\n\\]",
                "$$${formula.replace("\\", "\\\\")}$$")) {
                val view = newView()
                renderMarkdown(view, source, width, Color.BLACK, Color.BLUE)
                loadAndDraw(view, width)
                assertTrue(spans(view).any { it.drawable.destination.contains(
                    if (formula.contains("\\oiint")) "\\oiint_S" else "\\iiint_\\Omega") })
            }
        }
        val bare = newView()
        renderMarkdown(bare, formulas[1], 900, Color.BLACK, Color.BLUE)
        assertEquals(formulas[1], spans(bare).single().drawable.destination.trim())
        loadAndDraw(bare, 900)
    }

    @Test
    fun `streaming and copying retain the original closed integral command`() {
        for (formula in formulas) {
            val view = newView()
            val source = "推导：$$$formula$$ 继续计算"
            val tokenizer = StreamingMarkdownTokenizer()
            // Feed the same command in pieces, as an SSE response does.
            for (end in 1..source.length) {
                renderStreamingMarkdown(view, tokenizer.update(source.take(end)), 900, Color.BLACK, Color.BLUE)
            }
            val span = spans(view).single()
            assertEquals(formula, span.drawable.destination)
            loadAndDraw(view, 900)
            val text = view.text as Spanned
            assertEquals(formula, selectedMarkdownText(text, text.getSpanStart(span), text.getSpanEnd(span)))
        }
    }

    @Test
    fun `closed integrals add a visible loop without changing integral strokes or dimensions`() {
        for ((closed, open) in listOf("oiint" to "iint", "oiiint" to "iiint")) {
            for (style in listOf("\\displaystyle", "\\textstyle", "\\scriptstyle")) {
                val ordinary = nativeBitmap("$style\\$open")
                val surface = nativeBitmap("$style\\$closed")
                try {
                    assertEquals(ordinary.width, surface.width)
                    assertEquals(ordinary.height, surface.height)
                    var extraInk = 0
                    for (y in 0 until surface.height) for (x in 0 until surface.width) {
                        val a = ordinary.getPixel(x, y) ushr 24
                        val b = surface.getPixel(x, y) ushr 24
                        assertTrue("Original integral stroke lost", b >= a)
                        if (b > a) extraInk++
                    }
                    assertTrue("Closed operator needs visible closure ink", extraInk > 10)
                } finally { ordinary.recycle(); surface.recycle() }
            }
        }
    }

    @Test
    fun `Unicode closed operators and explicit limits render with stable side scripts`() {
        for ((command, unicode) in listOf("oiint" to "∯", "oiiint" to "∰")) {
            val before = nativeBitmap("\\${command}_S^T")
            val limits = nativeBitmap("\\$command\\limits_S^T")
            val after = nativeBitmap("\\${command}_S^T")
            val character = nativeBitmap("${unicode}_S^T")
            try {
                assertTrue("Explicit limits must add vertical space", limits.height > before.height)
                assertTrue("One formula must not change another's limits", before.sameAs(after))
                assertTrue("Unicode must draw the same operator", before.sameAs(character))
            } finally { before.recycle(); limits.recycle(); after.recycle(); character.recycle() }
        }
    }

    @Test
    fun `drawing a closure leaves following letters filled and preserves the triple integral domain`() {
        val ordinary = nativeBitmap("\\iint z\\,\\mathrm{d}S+\\Omega")
        val closed = nativeBitmap("\\oiint z\\,\\mathrm{d}S+\\Omega")
        try {
            val trailingWidth = minOf(ordinary.width, closed.width) / 2
            val a = Bitmap.createBitmap(ordinary, ordinary.width - trailingWidth, 0, trailingWidth, ordinary.height)
            val b = Bitmap.createBitmap(closed, closed.width - trailingWidth, 0, trailingWidth, closed.height)
            try { assertTrue("Closure must not change following text paint", a.sameAs(b)) }
            finally { a.recycle(); b.recycle() }
        } finally { ordinary.recycle(); closed.recycle() }
        val withoutDomain = nativeBitmap("\\iiint")
        val withDomain = nativeBitmap("\\iiint_\\Omega")
        fun ink(image: Bitmap): Int {
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            return pixels.count { it ushr 24 > 32 }
        }
        try {
            assertTrue("The integration domain must add visible ink", ink(withDomain) > ink(withoutDomain) + 30)
            File("build/reports/integral-domain.png").outputStream().use {
                withDomain.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        finally { withoutDomain.recycle(); withDomain.recycle() }
    }

    @Test
    fun `export loads all exact integrals without fallback and writes compact visual sample`() {
        val warnings = mutableListOf<String>()
        val source = formulas.joinToString("\n\n") { "$$$it$$" }
        val view = exportTextView(context, source, 1400, warning = { warnings += it })
        assertTrue(warnings.toString(), warnings.isEmpty())
        val actual = spans(view)
        assertEquals(3, actual.size)
        assertTrue(actual.all { it.drawable.hasResult() && it.drawable.result is JLatexMathDrawable })
        val sample = Bitmap.createBitmap(940, (view.height * 0.64f).toInt() + 36, Bitmap.Config.ARGB_8888)
        try {
            sample.eraseColor(Color.WHITE)
            Canvas(sample).apply { translate(20f, 18f); scale(0.64f, 0.64f); view.draw(this) }
            File("build/reports/integral-formulas.png").apply {
                parentFile?.mkdirs()
                outputStream().use { sample.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally { sample.recycle() }
    }

    @Test
    fun `Omega remains visible in the punctured domain and full divergence integral`() {
        val domain = """\Omega\setminus B_R"""
        val full = """I=\lim_{R\to0^+}\iiint_{\Omega\setminus B_R}
            \operatorname{div}\mathbf F\,dV""".trimIndent()
        for (formula in listOf(domain, full)) {
            val visible = nativeBitmap(formula)
            val phantom = nativeBitmap(formula.replace("\\Omega", "\\phantom{\\Omega}"))
            try {
                assertEquals("Hiding Omega must preserve the layout width", visible.width, phantom.width)
                assertEquals("Hiding Omega must preserve the layout height", visible.height, phantom.height)
                var omegaInk = 0
                for (y in 0 until visible.height) for (x in 0 until visible.width) {
                    if ((visible.getPixel(x, y) ushr 24) > (phantom.getPixel(x, y) ushr 24)) omegaInk++
                }
                assertTrue("Omega must paint visible ink in $formula", omegaInk > 30)
            } finally { visible.recycle(); phantom.recycle() }
            for (width in listOf(320, 900)) {
                for (source in listOf("$$\n$formula\n$$", "$$\n${formula.replace("\\", "\\\\")}\n$$")) {
                    val view = newView()
                    renderMarkdown(view, source, width, Color.BLACK, Color.BLUE)
                    assertTrue(spans(view).any { it.drawable.destination.contains(domain) })
                    loadAndDraw(view, width)
                }
            }
        }

        val streamed = newView()
        val inlineFull = full.replace(Regex("\\s+"), " ")
        val streamSource = "推导：$$$inlineFull$$ 继续计算"
        renderStreamingMarkdown(streamed, StreamingMarkdownTokenizer().update(streamSource), 900, Color.BLACK, Color.BLUE)
        val span = spans(streamed).single()
        assertEquals(inlineFull, span.drawable.destination)
        loadAndDraw(streamed, 900)
        val text = streamed.text as Spanned
        assertEquals(inlineFull, selectedMarkdownText(text, text.getSpanStart(span), text.getSpanEnd(span)))

        val source = "$$$domain$$\n\n$$\n$full\n$$"
        val warnings = mutableListOf<String>()
        val view = exportTextView(context, source, 1600, warning = { warnings += it })
        assertTrue(warnings.toString(), warnings.isEmpty())
        val sample = Bitmap.createBitmap(1020, (view.height * 0.6f).toInt() + 36, Bitmap.Config.ARGB_8888)
        try {
            sample.eraseColor(Color.WHITE)
            Canvas(sample).apply { translate(20f, 18f); scale(0.6f, 0.6f); view.draw(this) }
            File("build/reports/omega-domain-formulas.png").outputStream().use {
                sample.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { sample.recycle() }
    }
}
