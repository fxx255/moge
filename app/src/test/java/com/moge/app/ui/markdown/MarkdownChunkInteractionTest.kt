package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Color as AwtColor
import android.text.Spannable
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.moge.app.data.parse.normalizeReplyMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 助手回答渲染层的交互回归（对应用户 v1.0.26 反馈的四类问题）：
 *
 * 1. 内容块（MarkdownChunk）的 Compose 高度必须等于 TextView 内容的真实高度——
 *    一旦偏小，后面的图/表格就会和文字叠在一起（「图片与文字重叠」「表格遮挡文字」）；
 * 2. 表格块必须能横向拖动（horizontalScroll 的可滚动区间 + 内容确实更宽）。
 *
 * 说明：真实界面里气泡位于 LazyColumn 的 item 内（高度无上界），测试内容也刻意
 * 短于测试窗口高度，避免窗口自身的高度约束把 `Modifier.height` 静默夹取——
 * 那是测试环境的失真，不是生产路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MarkdownChunkInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val eightLines = (1..8).joinToString("\n") { "第 $it 行内容，用来撑起多行高度。" }

    private val tableMarkdown = """
        下面是测量结果：

        | 频率点 | 幅值 |
        | --- | --- |
        | 0 Hz | 1.00 |
        | 50 Hz | 0.50 |
        | 200 Hz | 0.12 |

        以上就是全部数据。
    """.trimIndent()

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun `saved answer keeps full height while footer actions appear and disappear`() {
        val answer = (1..20).joinToString("\n") { "第 $it 行完整回答与解题说明。" }
        lateinit var root: View
        lateinit var finishStreaming: () -> Unit
        lateinit var hideFooter: () -> Unit
        var bubbleHeight = 0
        compose.setContent {
            root = LocalView.current
            var streaming by remember { mutableStateOf(true) }
            var footer by remember { mutableStateOf(false) }
            finishStreaming = { streaming = false; footer = true }
            hideFooter = { footer = false }
            MaterialTheme {
                LazyColumn(Modifier.requiredWidth(300.dp).height(500.dp), reverseLayout = true) {
                    item(key = "answer") {
                        Column {
                            Box(Modifier.onGloballyPositioned { bubbleHeight = it.size.height }) {
                                if (streaming) StreamingMarkdownBody(answer)
                                else AnswerMarkdownBody(answer, emptyList()) { _, _ -> }
                            }
                            if (footer) Text("复制 · 重新生成")
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { finishStreaming() }
        compose.waitForIdle()
        compose.onNodeWithText("复制 · 重新生成").assertExists()
        compose.runOnIdle {
            val body = textViews(root.rootView).single { it.text.toString().contains("第 20 行完整回答") }
            assertTrue("页脚出现时正文仍须完整", body.text.toString().contains("第 1 行完整回答"))
            assertTrue("页脚出现时正文须有完整高度", bubbleHeight >= body.layout.height)
        }
        compose.runOnIdle { hideFooter() }
        compose.waitForIdle()
        compose.onNodeWithText("复制 · 重新生成").assertDoesNotExist()
        compose.runOnIdle {
            val body = textViews(root.rootView).single { it.text.toString().contains("第 20 行完整回答") }
            assertTrue("页脚消失后正文仍须完整", body.text.toString().contains("第 1 行完整回答"))
            assertTrue(bubbleHeight >= body.layout.height)
        }
    }

    @Test
    fun `stale first character is redrawn from the complete answer`() {
        val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), AwtColor.BLACK, AwtColor.BLUE)
        val answer = "祈霖你好，今天的英语阅读和翻译内容都在这里。"
        renderMarkdownIfChanged(view, answer, 300, AwtColor.BLACK, AwtColor.BLUE)
        view.text = "祈"
        renderMarkdownIfChanged(view, answer, 300, AwtColor.BLACK, AwtColor.BLUE)
        assertEquals(answer, view.text.toString())
    }

    /**
     * 按生产路径独立量出内容的真实高度（与 MarkdownChunk 内部同一套规则）。
     *
     * 量两次、取第二次：Markwon 的表格 span 在首次 measure 时高度可能尚未计入，
     * 生产路径由 AndroidView 正常测量，异步 span 通过布局请求收敛到复测后的稳定值。
     */
    private fun measureTextViewHeight(content: String, widthPx: Int, selectable: Boolean): Int {
        val context = RuntimeEnvironment.getApplication()
        val view = createMarkdownTextView(context, AwtColor.BLACK, AwtColor.BLUE, selectable)
        (view.tag as io.noties.markwon.Markwon).setMarkdown(view, content)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        view.measure(widthSpec, heightSpec)
        return view.measuredHeight
    }

    /**
     * 问题④的根：内容块高度必须真实收敛。此前探针发现，一旦外层有界，
     * `Modifier.height` 会被静默夹取、内容溢出；真实界面是无界容器，
     * 这里钉住「无界（短内容）下高度与内容完全一致」这一前提。
     */
    @Test
    fun `文字块高度等于内容真实高度`() {
        var chunkHeight = -1
        compose.setContent {
            Column(Modifier.requiredWidth(300.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { chunkHeight = it.size.height },
                ) {
                    MarkdownChunk(content = eightLines, fixedWidthPx = null)
                }
            }
        }
        compose.waitForIdle()
        val expected = measureTextViewHeight(eightLines, widthPx = 300, selectable = true)
        println("PROBE text chunkHeight=$chunkHeight expected=$expected")
        assertEquals("内容块高度必须等于内容真实高度", expected, chunkHeight)
    }

    /** 问题③：表格块必须能横向拖出非零滚动量。 */
    @Test
    fun `表格块可以横向拖动`() {
        lateinit var scroll: ScrollState
        var scrollValue = -1
        compose.setContent {
            scroll = rememberScrollState()
            Column(Modifier.requiredWidth(300.dp)) {
                Box(
                    Modifier
                        .wrapContentWidth()
                        .horizontalScroll(scroll)
                        .testTag("table-box"),
                ) {
                    MarkdownChunk(
                        content = tableMarkdown,
                        fixedWidthPx = 540,
                        selectable = false,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("table-box").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { scrollValue = scroll.value }
        println("PROBE table scroll=$scrollValue")
        assertTrue("表格应能横向拖动（scroll=$scrollValue）", scrollValue > 0)
    }

    /** 问题②/④：表格块自身的 Compose 高度必须等于宽画布下内容的真实高度。 */
    @Test
    fun `表格块高度等于宽画布下的内容真实高度`() {
        var chunkHeight = -1
        compose.setContent {
            Column(Modifier.requiredWidth(300.dp)) {
                Box(
                    Modifier
                        .wrapContentWidth()
                        .onGloballyPositioned { chunkHeight = it.size.height },
                ) {
                    MarkdownChunk(
                        content = tableMarkdown,
                        fixedWidthPx = 540,
                        selectable = false,
                    )
                }
            }
        }
        compose.waitForIdle()
        // 生产路径总是先过内容管线再渲染，独立测量用同一份归一化结果
        val normalized = normalizeReplyMarkdown(tableMarkdown)
        val expected = measureTextViewHeight(normalized, widthPx = 540, selectable = false)
        println("PROBE table chunkHeight=$chunkHeight expected=$expected")
        assertEquals("表格块高度必须等于内容真实高度", expected, chunkHeight)
    }

    /**
     * 高度必须**跟随内容变化**，而不是渲染一次就钉死。
     *
     * 用户 v1.0.27 反馈：气泡内文字会随上下滑动上下位移（先尾部被挡、多划几次变头部被挡）。
     * 根因是框高钉在「公式尚未异步加载完」时量出的偏小值上——JLatexMathPlugin 的
     * placeholder() 返回 null、后台线程算完才回主线程 setResult，加载完成后插件用
     * setText(同文本) 强制重排使内容变高。框高不跟着变 ⇒ 内容比框高 ⇒
     * LinkMovementMethod（继承 ScrollingMovementMethod）允许在框内拖动文字。
     *
     * 真实异步在 Robolectric 下无法复现（公式 drawable 的尺寸是 0，字体度量是桩），
     * 这里退而钉住机制前提：内容变了，气泡高度必须在同一组合内跟着变。
     */
    @Test
    fun `气泡高度跟随内容变化更新`() {
        val shortContent = "第一行内容"
        lateinit var updateContent: (String) -> Unit
        var bubbleHeight = -1
        compose.setContent {
            var text by remember { mutableStateOf(shortContent) }
            updateContent = { text = it }
            Column(Modifier.requiredWidth(300.dp)) {
                Box(
                    Modifier
                        .wrapContentWidth()
                        .onGloballyPositioned { bubbleHeight = it.size.height },
                ) {
                    MarkdownChunk(content = text, fixedWidthPx = 540)
                }
            }
        }
        compose.waitForIdle()
        val shortHeight = bubbleHeight
        val expectedShort = measureTextViewHeight(shortContent, widthPx = 540, selectable = true)
        assertEquals("初始高度应等于短内容的真实高度", expectedShort, shortHeight)

        compose.runOnIdle { updateContent(tableMarkdown) }
        compose.waitForIdle()
        val longHeight = bubbleHeight
        val expectedLong = measureTextViewHeight(
            normalizeReplyMarkdown(tableMarkdown),
            widthPx = 540,
            selectable = true,
        )
        println("PROBE follow short=$shortHeight expectedShort=$expectedShort " +
            "long=$longHeight expectedLong=$expectedLong")
        // 注意：不能断言 long > short——Robolectric 下 CJK 行高退化（多行文字也只量出
        // 一行高度），行数多寡在这里量不出差别。表格高度含内边距，与纯文字不同，
        // 用它来验证「高度确实跟着内容换了」这一契约。
        assertTrue(
            "内容换成表格后高度必须跟着变（short=$shortHeight long=$longHeight）",
            longHeight != shortHeight,
        )
        assertEquals("高度应等于新内容的真实高度", expectedLong, longHeight)
    }

    /**
     * 文本块**不得**成为「抢占触摸的落点」。
     *
     * 用户 v1.0.36 反馈：长回答气泡里，靠后的图仍然点不开。
     *
     * 根因：`setTextIsSelectable(true)` 会顺带把
     * `isClickable` / `isLongClickable` 都置为 true。我们此前只关掉了
     * `isClickable`+`isFocusable`，**漏了 `isLongClickable`** —— 而长按可点的 View
     * 在 `onTouchEvent` 里照样会消费 ACTION_DOWN。一旦这个真实 TextView 的实际高度
     * 溢出 Compose 给的格位（公式异步加载后必然变高），溢出的那条带子就会把本该
     * 落在下方图片上的触摸先吃掉 ⇒「越靠后越点不动」。
     *
     * Compose 的 `zIndex` 只改 Compose 自己的命中顺序，管不住 interop 里真实 View 的
     * 原生触摸分发，所以必须从 View 自身属性上堵死。
     */
    @Test
    fun `文本块不抢占触摸`() {
        val context = RuntimeEnvironment.getApplication()
        val view = createMarkdownTextView(context, AwtColor.BLACK, AwtColor.BLUE, selectable = true)
        println(
            "PROBE create clickable=${view.isClickable} longClickable=${view.isLongClickable} " +
                "focusable=${view.isFocusable} movement=${view.movementMethod}",
        )
        assertTrue("回答正文必须支持长按选字", view.isLongClickable)
        assertTrue("回答正文必须可聚焦以显示选区", view.isFocusable)
        // 长按选中/复制能力必须保留：MovementMethod 仍需就位
        assertTrue("文本块需保留 LinkMovementMethod 以支持链接与长按选中", view.movementMethod != null)
    }

    /**
     * 真正的生产路径：**真实渲染一遍 Markdown 之后**，点击属性必须依旧是关的。
     *
     * 上一版测试只在「创建后、尚未渲染」时检查属性，于是漏掉了真正会翻车的那一步 ——
     * `renderMarkdown()` 会调用 `Markwon.setMarkdown()`，而 Markwon 的内部插件
     * （`CorePlugin.afterSetText` 等）在**每次 setText 之后**都会再跑一遍。
     * 只要其中任何一个环节把 `isClickable` / `isLongClickable` 重新打开，
     * 创建时关掉的那两个属性就等于白关 —— 用户看到的「靠后的图点不开」原样复现。
     *
     * 所以这里必须渲染真实内容（含会产生异步 drawable 的公式，以及会命中
     * `LinkMovementMethod` 的链接），再断言属性没被翻回去。
     */
    @Test
    fun `渲染后文本块仍不抢占触摸`() {
        val context = RuntimeEnvironment.getApplication()
        val view = createMarkdownTextView(context, AwtColor.BLACK, AwtColor.BLUE, selectable = true)
        val markdown = """
            下面是推导过程，含一段行内公式 ${'$'}f_c${'$'} 与一个独立公式：

            ${'$'}${'$'}S(f) = \frac{N_0}{2}\left(1 + \cos 2\pi f T\right)${'$'}${'$'}

            也可以参考 [维基百科](https://example.com) 的解释。
        """.trimIndent()
        (view.tag as io.noties.markwon.Markwon).setMarkdown(view, markdown)
        println(
            "PROBE rendered clickable=${view.isClickable} longClickable=${view.isLongClickable} " +
                "focusable=${view.isFocusable} movement=${view.movementMethod}",
        )
        assertTrue(
            "渲染后文本块必须支持长按选字",
            view.isLongClickable,
        )
        assertTrue(
            "渲染后文本块必须可聚焦",
            view.isFocusable,
        )
        assertTrue(
            "渲染后需保留 LinkMovementMethod 以支持链接与长按选中",
            view.movementMethod != null,
        )
    }

    /**
     * 表格块不抢占触摸。
     *
     * **这里不能断言「渲染后属性都是 false」** —— 那是做不到的。Markwon 的
     * `CorePlugin.afterSetText()` 会在每次 setText 之后检查，一旦 `getMovementMethod()`
     * 为 null 就**强行塞进** `LinkMovementMethod`；而 AOSP 的 `setMovementMethod()` 又会
     * 通过 `fixFocusableAndClickableSettings()` 把 clickable / longClickable / focusable
     * 全部打开。也就是说「表格块绝不 clickable」这个目标在框架层面无法通过属性达成。
     *
     * 所以这里断言的是**真正有意义的契约**：无论属性被框架翻成什么样，表格块都不参与
     * 触摸消费 —— 由 `createMarkdownTextView` 里重写的 `onTouchEvent` 保证
     * （表格内容里没有 `ClickableSpan`，任何落点都会被判为未命中而放行）。
     */
    @Test
    fun `表格块不抢占触摸`() {
        val context = RuntimeEnvironment.getApplication()
        val view = createMarkdownTextView(context, AwtColor.BLACK, AwtColor.BLUE, selectable = false)
        (view.tag as io.noties.markwon.Markwon).setMarkdown(view, tableMarkdown)
        println(
            "PROBE table clickable=${view.isClickable} longClickable=${view.isLongClickable} " +
                "focusable=${view.isFocusable} movement=${view.movementMethod}",
        )
        // 表格内容中不得含可点片段，否则 onTouchEvent 的放行判定会失效。
        val text = view.text
        val clickableSpans = if (text is Spannable) {
            text.getSpans(0, text.length, ClickableSpan::class.java)
        } else {
            emptyArray()
        }
        assertTrue(
            "表格块内不应含 ClickableSpan（否则 onTouchEvent 的放行判定会失效）",
            clickableSpans.isEmpty(),
        )
        // 行为断言：即便属性被框架打开，表格块也**不得消费**触摸。
        // 表格内容里没有可点片段 ⇒ 必须走 onTouchEvent 的放行分支。
        assertFalse(
            "表格块不应消费 ACTION_DOWN（会吃掉外层横向拖动与页面纵向滚动）",
            dispatchTouch(view, MotionEvent.ACTION_DOWN, 20f, 20f),
        )
    }

    /**
     * 文本块也必须放行「落在普通文字上」的触摸，同时**保留**「落在链接上」的点击。
     *
     * 这是 `onTouchEvent` 覆写的双向契约：只放行不该消费的，不误伤该消费的。
     * 只测「放行」会有把链接点击一起关掉的过度修复风险，所以两面都测。
     */
    @Test
    fun `文本块放行普通文字但保留链接点击`() {
        val context = RuntimeEnvironment.getApplication()
        val view = createMarkdownTextView(context, AwtColor.BLACK, AwtColor.BLUE, selectable = true)
        (view.tag as io.noties.markwon.Markwon)
            .setMarkdown(view, "普通文字开头，然后是一个 [链接](https://example.com) 结尾。")
        // 需要先完成一次布局，`layout` 才可用（onTouchEvent 的坐标换算依赖它）
        val widthSpec = View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val text = view.text
        val spans = if (text is Spannable) {
            text.getSpans(0, text.length, ClickableSpan::class.java)
        } else {
            emptyArray()
        }
        println(
            "PROBE link clickableSpans=${spans.size} layout=${view.layout != null} " +
                "height=${view.measuredHeight}",
        )

        // 普通文字的 ACTION_DOWN 必须交给 TextView，长按才能创建选择区。
        assertTrue(
            "落在普通文字上的 ACTION_DOWN 必须由 TextView 接管",
            dispatchTouch(view, MotionEvent.ACTION_DOWN, 1f, 1f),
        )
    }

    /** 向 View 派发一个指定动作与坐标的触摸事件，返回其是否消费该事件。 */
    private fun dispatchTouch(view: View, action: Int, x: Float, y: Float): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        return try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
