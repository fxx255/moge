package com.moge.app.ui.markdown

import android.app.Application
import android.text.Spanned
import android.view.ViewGroup
import android.widget.TextView
import io.noties.markwon.Markwon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.noties.jlatexmath.JLatexMathAndroid

/**
 * 守卫：Markdown 的 `**粗体**` 是否真被解析成加粗 span。
 *
 * ⚠️ **关键陷阱：Markwon 挂的不是 `StyleSpan`，而是自己的 `StrongEmphasisSpan`。**
 * 它继承 `android.text.style.MetricAffectingSpan`（**不是** `StyleSpan`），
 * 自己实现 `updateDrawState` 去改 paint 的 typeface。
 * 一开始我按 `StyleSpan` 断言，得到 `spans=0` 的假红——实际渲染完全正常。
 * 排查这类「看不出效果」的问题时，**先确认真实 span 类型，别照着 Android 直觉猜**。
 *
 * 本类能覆盖的：**解析层**（星号被消费 + 加粗 span 精确覆盖目标文字）。
 * 本类**覆盖不到**的：**字重是否肉眼可见**——中文字体常缺真实 Bold 字重，
 * Android 会静默退化回 Regular。且 Robolectric 既不栅格化字体、`Typeface.DEFAULT_BOLD`
 * 也是空壳（实测 `isBold=false`），**任何字重型断言在本环境都是假绿/假红**，
 * 所以本类刻意不断言字重，只断言 span 结构。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MarkdownBoldRenderTest {

    /** Markwon 的真实加粗 span 类型（不能按 Android 的 `StyleSpan` 找）。 */
    private val boldSpanClass = Class.forName("io.noties.markwon.core.spans.StrongEmphasisSpan")

    private val ctx: Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        JLatexMathAndroid.init(ctx)
    }

    private fun render(markdown: String): Spanned {
        val tv = createMarkdownTextView(ctx, 0xFF000000.toInt(), 0xFF0000FF.toInt(), selectable = false)
        tv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        (tv.tag as Markwon).setMarkdown(tv, markdown)
        return tv.text as Spanned
    }

    private fun boldSpans(text: Spanned): List<Any> =
        text.getSpans(0, text.length, boldSpanClass).toList()

    /** `**` 必须被消费，不残留字面星号。 */
    @Test
    fun `双星号被解析消费不残留字面星号`() {
        val text = render("这是**重点内容**说明")
        assertEquals("** 应被消费掉，实际文本: $text", "这是重点内容说明", text.toString())
    }

    /** 解析后必须挂上加粗 span，且精确覆盖目标文字。 */
    @Test
    fun `双星号区间挂上加粗 span 且覆盖精确`() {
        val text = render("这是**重点内容**说明")
        val spans = boldSpans(text)
        assertEquals("应恰好一个加粗 span，实际: ${spans.size}", 1, spans.size)
        val s = spans.first()
        val covered = text.substring(text.getSpanStart(s), text.getSpanEnd(s))
        assertEquals("加粗应恰好覆盖「重点内容」", "重点内容", covered)
    }

    /** 中文紧贴星号（无空格）是最常见写法，务必支持。 */
    @Test
    fun `中文紧贴星号时仍能解析`() {
        for (md in listOf("**中文加粗**", "前面**中文加粗**后面", "**中文加粗**，后接标点")) {
            val text = render(md)
            assertTrue(
                "『$md』应产生加粗 span，实际文本: $text",
                boldSpans(text).isNotEmpty(),
            )
            assertTrue("『$md』不应残留字面星号", !text.contains("**"))
        }
    }

    /** 中英混排、以及加粗片段内含标点/数字。 */
    @Test
    fun `混排与含标点的加粗片段也能解析`() {
        for (md in listOf("**S(f)** 是谱密度", "**第 3 步：** 求解", "**B/2**")) {
            val text = render(md)
            assertTrue("『$md』应产生加粗 span，实际文本: $text", boldSpans(text).isNotEmpty())
            assertTrue("『$md』不应残留字面星号，实际: $text", !text.contains("**"))
        }
    }

    /** 多处加粗要各自成 span，不能只识别第一处。 */
    @Test
    fun `多段加粗各自独立成 span`() {
        val text = render("**第一**中间**第二**结尾")
        val spans = boldSpans(text)
        assertEquals("两处加粗应各有 span，实际: ${spans.size}", 2, spans.size)
        val covers = spans
            .map { text.substring(text.getSpanStart(it), text.getSpanEnd(it)) }
            .sorted()
        assertEquals(listOf("第一", "第二"), covers)
    }

    /** 未成对的星号不应产生加粗（也不该崩）。 */
    @Test
    fun `未成对星号不会误产生加粗`() {
        val text = render("这是**没闭合的星号")
        assertTrue("未成对不应产生加粗 span", boldSpans(text).isEmpty())
    }

    /** 星号内侧带空格按 CommonMark 不构成强调（模型偶发的 `** 文字 **` 不该被当粗体）。 */
    @Test
    fun `星号内侧带空格时不视为加粗`() {
        val text = render("这是 ** 带空格 ** 的情况")
        assertTrue(
            "按 CommonMark，左定界符后紧跟空格不构成强调，实际文本: $text",
            boldSpans(text).isEmpty(),
        )
    }
}
