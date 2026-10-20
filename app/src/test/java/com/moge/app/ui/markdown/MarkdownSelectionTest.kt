package com.moge.app.ui.markdown

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.widget.TextView
import com.moge.app.ui.solve.userMathSource
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathAndroid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownSelectionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val formula = "\\frac{1}{2}+x^2"
    @Before fun initializeMath() { JLatexMathAndroid.init(context) }

    private fun render(source: String): TextView =
        createMarkdownTextView(context, Color.BLACK, Color.BLUE).also {
            renderMarkdown(it, source, 1000, Color.BLACK, Color.BLUE)
        }
    private fun spans(view: TextView) = (view.text as Spanned)
        .getSpans(0, view.text.length, BaselineLatexSpan::class.java).sortedBy { (view.text as Spanned).getSpanStart(it) }
    private fun clipboard() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Test fun `copied series followed immediately by prose retains both formula boundaries`() {
        val first = "=\\sum_{n=0}^{\\infty}(-1)^n\\frac{(2n-1)!!}{2^n n!}u^n"
        val second = "=\\sum_{n=0}^{\\infty}(-1)^n\\frac{(2n)!}{4^n(n!)^2}u^n"
        val original = render("\$\$\n$first\n\$\$\n\n\$\$\n$second\n\$\$")
        val text = original.text as Spannable
        val formulas = spans(original)
        Selection.setSelection(text, text.getSpanStart(formulas.first()), text.getSpanEnd(formulas.last()))
        original.onTextContextMenuItem(android.R.id.copy)
        val copied = clipboard().primaryClip!!.getItemAt(0).text.toString()
        for (prose in listOf("这一步没看懂", "why is this equal", "\n这一步没看懂")) {
            val source = requireNotNull(userMathSource(copied + prose))
            assertFalse(source.incomplete)
            assertEquals(prose, listOf(first, second), source.formulas)
            val sent = render(source.renderSource)
            assertEquals(prose, listOf(first, second), spans(sent).map { it.drawable.destination.trim() })
            assertTrue(sent.text.toString().endsWith(prose.trimStart()))
        }
    }

    @Test fun `selection within a fraction snaps to that item and copies through native menu`() {
        val view = render("前文 \$\$$formula\$\$ 后文")
        val text = view.text as Spannable
        val span = spans(view).single()
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        Selection.setSelection(text, start + 2, start + formula.indexOf('+') - 2)
        assertEquals(start, view.selectionStart)
        assertEquals(start + formula.indexOf('+'), view.selectionEnd)
        assertTrue(view.onTextContextMenuItem(android.R.id.copy))
        assertEquals("\$\$\\frac{1}{2}\$\$", clipboard().primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun `exact formula boundaries and backward selection preserve complete source`() {
        val view = render("前 \$\$$formula\$\$ 后")
        val text = view.text as Spannable
        val span = spans(view).single()
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        assertEquals(formula, selectedMarkdownText(text, start, end))
        Selection.setSelection(text, end - 1, start + 1)
        assertEquals(end, view.selectionStart)
        assertEquals(start, view.selectionEnd)
        view.onTextContextMenuItem(android.R.id.copy)
        assertEquals("\$\$$formula\$\$", clipboard().primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun `text ending at formula boundary does not include neighboring formula`() {
        val view = render("前文 \$\$$formula\$\$ 后文")
        val text = view.text as Spanned
        val span = spans(view).single()
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        assertEquals("前文 ", selectedMarkdownText(text, 0, start))
        assertEquals(" 后文", selectedMarkdownText(text, end, text.length))
        assertEquals("", selectedMarkdownText(text, start + 1, start + 1))
    }

    @Test fun `mixed selection includes only selected formulas and surrounding text`() {
        val view = render("前 \$\$$formula\$\$ 中 \$\$y_1\$\$ 后")
        val text = view.text as Spanned
        val formulas = spans(view)
        assertEquals("前 $formula 中 y_1 后", selectedMarkdownText(text, 0, text.length))
        assertEquals("$formula 中 ", selectedMarkdownText(text,
            text.getSpanStart(formulas.first()) + 1, text.getSpanStart(formulas.last())))
        assertEquals(" 中 y_1", selectedMarkdownText(text,
            text.getSpanEnd(formulas.first()), text.getSpanEnd(formulas.last()) - 1))
    }

    @Test fun `block matrix copy preserves latex newlines hidden by placeholder`() {
        val latex = "\\begin{pmatrix}\n1 & 2 \\\\\n3 & 4\n\\end{pmatrix}"
        val view = render("\$\$\n$latex\n\$\$")
        val text = view.text as Spanned
        val span = spans(view).single()
        assertFalse(text.toString().contains("\n1 & 2"))
        assertEquals(latex, selectedMarkdownText(text, text.getSpanStart(span), text.getSpanEnd(span)))
        Selection.setSelection(text as Spannable, text.getSpanStart(span), text.getSpanEnd(span))
        view.onTextContextMenuItem(android.R.id.copy)
        val copied = clipboard().primaryClip!!.getItemAt(0).text.toString()
        val source = requireNotNull(userMathSource(copied + "这个矩阵"))
        assertEquals(listOf(latex), source.formulas)
        assertEquals(latex, spans(render(source.renderSource)).single().drawable.destination.trim())
    }

    @Test fun `plain native selection retains exact selected characters`() {
        val view = render("abc 中文 def")
        Selection.setSelection(view.text as Spannable, 4, 6)
        view.onTextContextMenuItem(android.R.id.copy)
        assertEquals("中文", clipboard().primaryClip!!.getItemAt(0).text.toString())
    }
}
