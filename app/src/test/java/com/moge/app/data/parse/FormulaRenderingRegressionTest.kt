package com.moge.app.data.parse

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable
import com.moge.app.ui.markdown.createMarkdownTextView
import io.noties.markwon.Markwon

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FormulaRenderingRegressionTest {
    @Before fun init() { JLatexMathAndroid.init(RuntimeEnvironment.getApplication()) }

    @Test fun `screenshot seven mixed multiline delimiters are consumed by actual Markwon`() {
        val markdown = "**结论先说**：$$\n\\rho^2=\\cos 2\\theta\n" +
            "$$ 的图像，两瓣分别占据 $$\\left[-\\tfrac{\\pi}{4},\\tfrac{\\pi}{4}\\right]$$（右瓣）与 " +
            "$$\\left[\\tfrac{3\\pi}{4},\\tfrac{5\\pi}{4}\\right]$$（左瓣）。"
        for (width in listOf(300, 900)) {
            val prepared = wrapLongFormulas(sanitizeReplyLatex(normalizeReplyMarkdown(markdown)), width) {
                JLatexMathDrawable.builder(it).textSize(36f).build().intrinsicWidth
            }
            val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), android.graphics.Color.BLACK,
                android.graphics.Color.BLUE, selectable = false)
            (view.tag as Markwon).setMarkdown(view, prepared)
            assertFalse(view.text.toString(), view.text.contains("$$"))
            Regex("""\$\$([\s\S]*?)\$\$""").findAll(prepared).forEach {
                JLatexMathDrawable.builder(it.groupValues[1].trim()).textSize(36f).build()
            }
        }
    }

    @Test fun `power spectrum derivation stays renderable at phone and tablet widths`() {
        val formulas = listOf(
            """\begin{aligned}
                P_{y_c}(f)&=P_{y_s}(f)\\[6pt]
                &=P_Y(f+f_c)+P_Y(f-f_c)\\
                &=4\pi^2N_0\left(f_c^2+f^2\right)
                \end{aligned}""".trimIndent(),
            """P_{y_c}(f)=P_{y_s}(f)=4\pi^2N_0\left(f_c^2+f^2\right),\quad |f|\le\frac{B}{2}""",
            """P(f)=
                \begin{cases}4\pi^2N_0\left(f_c^2+f^2\right)&|f|\le B/2\\0&|f|>B/2\end{cases}""".trimIndent(),
            """P(f)=\left|a+b+c+d\right|+z""",
            """P(f)=\left.f(x)+g(x)\right|_{x=0}+z""",
            """\begin{aligned}a&=\left|b+c\\+d\right|\end{aligned}""",
            """P(f)=a+b\\=P_Y(f+f_c)+P_Y(f-f_c)\\=c+d""",
        )
        for (formula in formulas) {
            for (width in listOf(160, 400, 900)) {
                val prepared = wrapLongFormulas(
                    sanitizeReplyLatex(normalizeReplyMarkdown("$$\n$formula\n$$")),
                    width,
                ) { JLatexMathDrawable.builder(it).textSize(36f).build().intrinsicWidth }
                val pieces = Regex("""\$\$([\s\S]*?)\$\$""").findAll(prepared).toList()
                assertFalse(prepared, pieces.isEmpty())
                pieces.forEach { piece ->
                    val latex = piece.groupValues[1].trim()
                    // Exercise the actual parser, not a character-count proxy.
                    JLatexMathDrawable.builder(latex).textSize(36f).build()
                }
            }
        }
    }

    @Test fun `unwrapping aligned preserves the first grouped expression`() {
        val pieces = splitFormula("""\begin{aligned}{a+b}&=c\\d&=e\end{aligned}""", 1) { it.length }
        assertEquals("{a+b}=c d=e", pieces.joinToString(" ").replace(" =", "="))
    }
}
