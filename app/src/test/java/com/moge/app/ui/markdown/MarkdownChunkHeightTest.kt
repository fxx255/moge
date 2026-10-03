package com.moge.app.ui.markdown

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Markdown 片段的高度必须由内容真实上报。
 *
 * 背景：v1.0.24 用户反馈「只有第一张图能点开，后面的图和表格都点不动」。
 * 根因是 [androidx.compose.ui.viewinterop.AndroidView] 在测量阶段拿到的是一个
 * **刚创建、还空着的** TextView（内容要等 update / LaunchedEffect 才写入），
 * 量出来只有一行高度；等文字/公式/表格真正填进去，实际高度早已超出这个数字，
 * 父 Column 仍按「一行高」给后续兄弟节点排布 ⇒ 后面的图与表格被压进前面撑开的
 * 区域里，触摸命中错乱。
 *
 * 当前生产代码在创建 View 前取得约束宽度并填入正文，再由 AndroidView 正常测量。
 * 这里用真实 TextView 验证这个前提：**先测量后填内容，量到的高度会明显偏小**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MarkdownChunkHeightTest {

    /**
     * 造一个和 AndroidView 里一致的 TextView。
     *
     * 必须挂 LayoutParams：Robolectric 下没有它 measure 会直接 NPE
     * （`this.mLayoutParams is null`），这本身也说明「TextView 的测量依赖外部给它布局参数」——
     * 正是 AndroidView 负责提供的那部分。
     */
    private fun newTextView(): TextView =
        TextView(RuntimeEnvironment.getApplication()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            textSize = 17f
        }

    private fun measureHeight(view: TextView, widthPx: Int): Int {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        return view.measuredHeight
    }

    @Test
    fun `空视图测出来只有很小的高度`() {
        val view = newTextView()
        val empty = measureHeight(view, 800)
        assertTrue("空视图也应能量出高度（至少一行）", empty > 0)
        // 一行高度大致是 textSize 的 1~2 倍。它就是 AndroidView 首次测量时上报给
        // Compose 的数字——内容真正填进去之前，后面的兄弟节点都会被按这个高度排布。
        assertTrue("空视图应该是「一行」量级，实际 $empty", empty < 17f * 3)
    }

    @Test
    fun `先测量后填内容，量到的高度明显偏小`() {
        // 完全复刻 AndroidView 的时序：factory 建空视图 → Compose 测量 → update 才写内容
        val view = newTextView()
        val heightWhenEmpty = measureHeight(view, 800)
        view.text = (1..30).joinToString("\n") { "第 $it 行文字内容" }
        val heightWithContent = measureHeight(view, 800)

        assertTrue(
            "空视图量出的高度必须远小于有内容时（空=$heightWhenEmpty 有内容=$heightWithContent）" +
                "——两者差距就是被压进溢出区的高度",
            heightWithContent > heightWhenEmpty * 5,
        )
    }

    @Test
    fun `重新测量能拿到内容的真实高度`() {
        val view = newTextView()
        // 先量一次（模拟首次布局），再填内容、再量一次（模拟 AndroidView 收到布局请求后的正常复测）
        val heightWhenEmpty = measureHeight(view, 800)
        view.text = (1..20).joinToString("\n") { "第 $it 行" }
        val remeasured = measureHeight(view, 800)
        assertTrue(
            "重新测量应拿到多行内容的真实高度（空=$heightWhenEmpty 重新测量=$remeasured）",
            remeasured > heightWhenEmpty,
        )
    }

    /**
     * 修复的关键：`AndroidView` 首次测量时视图还是空的，量到的高度会**一直被沿用**。
     *
     * 这里复刻真实时序并确认「不主动重新测量」的后果 —— 量到的始终是空视图那个很小的值，
     * 而这正是后续兄弟节点被错误排布、触摸命中错乱的源头。
     * 生产代码先渲染再交给 AndroidView 测量；异步 span 更新通过正常布局请求复测。
     */
    @Test
    fun `不主动重测就会一直沿用空视图的高度`() {
        val view = newTextView()
        val firstLayout = measureHeight(view, 800) // AndroidView 的首次测量
        view.text = (1..40).joinToString("\n") { "第 $it 行内容" }
        // 不调用 measure 就取 height：拿到的仍是首次测量的结果，而不是内容的真实高度
        val stale = view.measuredHeight
        assertTrue(
            "未重新测量时 height 应仍等于首次布局值（first=$firstLayout stale=$stale）",
            stale == firstLayout,
        )
        // 主动重测后才会更新 —— 这就是修复所依赖的动作
        val fresh = measureHeight(view, 800)
        assertTrue("主动重测后高度应更新（fresh=$fresh stale=$stale）", fresh > stale)
    }
}
