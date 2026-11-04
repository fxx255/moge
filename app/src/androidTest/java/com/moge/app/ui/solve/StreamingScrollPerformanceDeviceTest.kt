package com.moge.app.ui.solve

import android.os.Handler
import android.os.Debug
import android.os.HandlerThread
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/** Same-device diagnostic; frame times are recorded, never asserted against a flaky FPS target. */
@RunWith(AndroidJUnit4::class)
class StreamingScrollPerformanceDeviceTest {
    @get:Rule val scenario = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: ComponentActivity

    // ActivityScenario waits for Android idle when queried off-main; cache before streaming starts.
    @Before fun cacheActivity() { scenario.scenario.onActivity { activity = it } }
    @After fun releaseContent() { runUi { activity.setContent {} } }

    // Use the real app frame clock: a Compose test clock would distort this performance probe.
    private fun content(block: @Composable () -> Unit) { runUi { activity.setContent(content = block) } }
    private fun runUi(block: () -> Unit) { instrumentation.runOnMainSync { block() } }
    private fun await(timeout: Long, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (!predicate()) {
            assertTrue("Timed out waiting for the native reading view", SystemClock.uptimeMillis() < end)
            SystemClock.sleep(20)
        }
    }

    @Test fun draggingKeepsDisplayingArrivalsAndCompletion() {
        var source by mutableStateOf((1..25).joinToString("\n\n") { paragraph(it) })
        var status by mutableStateOf(AnswerState.STREAMING)
        content { MogeTheme(Appearance.PAPER) {
            SolveList(SolveUiState(conversationId = "scroll-buffer-test", items = listOf(SolveItem.Answer("answer", status, source))),
                {}, {}, { _, _ -> }, {}, {}, initialViewport = ConversationViewport("answer", 0, 0))
        } }
        await(15_000) {
            var ready = false
            runUi { ready = native().any { it.text.contains("第 25 步") && it.layout != null } }
            ready
        }
        swipe(up = true) {
            lateinit var view: TextView
            runUi {
                view = native().single { it.text.contains("第 25 步") }
                source += "\n\n拖动中持续显示 **结构：****recommend**。"
            }
            SystemClock.sleep(250)
            runUi {
                assertSame(view, native().single { it.text.contains("第 25 步") })
                assertTrue("New ink must appear before the finger lifts", view.text.contains("拖动中持续显示 结构：recommend。"))
                source += "\n\n生成完成，所有内容都保留。"
                status = AnswerState.COMPLETED
            }
            SystemClock.sleep(250)
            runUi { assertTrue("Completion must display while the finger is still down", view.text.endsWith("生成完成，所有内容都保留。")) }
        }
        await(10_000) {
            var ready = false
            runUi { ready = native().any { it.text.endsWith("生成完成，所有内容都保留。") } }
            ready
        }
        runUi {
            val rendered = native().single { it.text.contains("第 25 步") }.text
            assertTrue(rendered.contains("拖动中持续显示 结构：recommend。"))
            assertEquals(rendered.toString(), com.moge.app.ui.markdown.selectedMarkdownText(rendered as android.text.Spanned, 0, rendered.length))
        }
    }

    @Test fun recordScrollFramesWhileLongFormattedAnswerGrows() {
        var source by mutableStateOf((1..55).joinToString("\n\n") { paragraph(it) })
        var generating by mutableStateOf(true)
        var receiving by mutableStateOf(false)
        val arrivals = AtomicInteger()
        content { MogeTheme(Appearance.PAPER) {
            LaunchedEffect(generating, receiving) {
                if (generating && receiving) for (chunk in (56..100).joinToString("\n\n", prefix = "\n\n") { paragraph(it) }.chunked(8)) {
                    delay(80)
                    source += chunk
                    arrivals.incrementAndGet()
                }
            }
            SolveList(SolveUiState(conversationId = "stream-scroll-profile", generating = generating,
                items = listOf(SolveItem.Answer("answer", if (generating) AnswerState.STREAMING else AnswerState.COMPLETED, source))),
                {}, {}, { _, _ -> }, {}, {}, initialViewport = ConversationViewport("answer", 0, 0))
        } }
        await(15_000) {
            var ready = false
            runUi { ready = native().any { it.text.contains("第 55 步") } }
            ready
        }
        SystemClock.sleep(400)
        val changes = AtomicInteger()
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changes.incrementAndGet() }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        runUi { native().filter { it.text.contains("第 55 步") }.forEach { it.addTextChangedListener(watcher) } }
        runUi { receiving = true }
        val receivingBefore = arrivals.get()
        val trace = InstrumentationRegistry.getArguments().getString("cpuTrace") == "true"
        if (trace) Debug.startMethodTracingSampling(File(activity.filesDir, "stream-scroll.trace").path, 16 * 1024 * 1024, 2_000)
        val live = try { measureFrames(changes) { repeat(3) { swipe(up = true); swipe(up = false) } } }
            finally { if (trace) Debug.stopMethodTracing() }
        assertTrue("The source must keep receiving while the reader scrolls", arrivals.get() > receivingBefore)
        assertTrue("Displayed text must keep changing during scrolling", changes.get() > 0)
        runUi { generating = false }
        SystemClock.sleep(800)
        changes.set(0)
        val completed = measureFrames(changes) { repeat(3) { swipe(up = true); swipe(up = false) } }
        val label = InstrumentationRegistry.getArguments().getString("profileLabel") ?: "current"
        val qa = File(activity.filesDir, "stream-scroll-qa").apply { mkdirs() }
        File(qa, "$label.txt").writeText("streaming: $live\ncompleted: $completed\narrivals: ${arrivals.get()}\n")
    }

    private fun paragraph(index: Int) = "第 $index 步：**结构：****recommend** 后接建议的内容；" +
        "**enable sb. to do sth.** 表示使某人能够做某事。结合条件 ${'$'}x_${index}=\\frac{${index}+1}{2}${'$'} 检查推导，保留阅读位置并继续分析。"

    private fun measureFrames(changes: AtomicInteger, action: () -> Unit): String {
        val durations = Collections.synchronizedList(mutableListOf<Long>())
        val layouts = Collections.synchronizedList(mutableListOf<Long>())
        val worker = HandlerThread("scroll-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, frame, _ ->
            if (frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                durations += frame.getMetric(FrameMetrics.TOTAL_DURATION)
                layouts += frame.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
            }
        }
        runUi { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(worker.looper)) }
        changes.set(0)
        try { action() } finally {
            runUi { activity.window.removeOnFrameMetricsAvailableListener(listener) }
            worker.quitSafely()
            worker.join(1_000)
        }
        fun List<Long>.percentile(p: Double) = if (isEmpty()) -1.0 else sorted()[(ceil(size * p).toInt() - 1).coerceAtLeast(0)] / 1_000_000.0
        val frames = synchronized(durations) { durations.toList() }
        val measure = synchronized(layouts) { layouts.toList() }
        assertTrue("FrameMetrics must observe the scrolling window", frames.size > 20)
        return "frames=${frames.size}, p50Ms=${frames.percentile(.50)}, p95Ms=${frames.percentile(.95)}, " +
            "over32ms=${frames.count { it > 32_000_000 }}, layoutP95Ms=${measure.percentile(.95)}, textReplacements=${changes.get()}"
    }

    private fun swipe(up: Boolean, duringDrag: (() -> Unit)? = null) {
        val decor = activity.window.decorView
        val x = decor.width * .55f
        val start = decor.height * if (up) .78f else .24f
        val end = decor.height * if (up) .24f else .78f
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, start)
        try {
            for (step in 1..30) {
                SystemClock.sleep(16)
                send(MotionEvent.ACTION_MOVE, start + (end - start) * step / 30f)
                if (step == 12) duringDrag?.invoke()
            }
        } finally { send(MotionEvent.ACTION_UP, end) }
        SystemClock.sleep(100)
    }

    private fun native(view: View = activity.window.decorView): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { native(view.getChildAt(it)) }
        else -> emptyList()
    }
}
