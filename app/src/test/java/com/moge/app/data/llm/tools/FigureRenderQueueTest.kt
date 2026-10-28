package com.moge.app.data.llm.tools

import android.app.Application
import com.moge.app.data.parse.ReplyFigure
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FigureRenderQueueTest {
    @Test fun independentRendersAreBoundedAndCompletionWaitsForThem() = runTest {
        val release = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        var finished = 0
        val tools = FigureTools(this) {
            active++; peak = maxOf(peak, active)
            try { release.await(); finished++; true } finally { active-- }
        }
        for (id in 1..4) {
            val result = tools.execute("render_figure", FigureToolsTest.args(id, "plot", FigureToolsTest.CURVE))
            assertTrue(result.contains("accepted"))
        }
        assertEquals(2, active)
        assertEquals(List(4) { ReplyFigure.Missing }, tools.figures)
        val completion = async { tools.awaitRenders() }
        runCurrent()
        assertFalse(completion.isCompleted)
        release.complete(Unit)
        completion.await()
        assertEquals(4, finished)
        assertEquals(2, peak)
        assertTrue(tools.figures.all { it is ReplyFigure.Plot })
    }

    @Test fun revisionsRunInOrderAndFailedRevisionKeepsLastSuccessfulSpec() = runTest {
        val release = CompletableDeferred<Unit>()
        val titles = mutableListOf<String>()
        val tools = FigureTools(this) { figure ->
            val title = (figure as ReplyFigure.Plot).spec.title
            titles += title
            if (title == "抛物线") { release.await(); true } else false
        }
        val original = FigureToolsTest.args(1, "plot", FigureToolsTest.CURVE)
        tools.execute("render_figure", original)
        tools.execute("render_figure", original) // A duplicate shares the pending render.
        tools.execute("render_figure", FigureToolsTest.args(1, "plot", FigureToolsTest.CURVE.replace("抛物线", "修订")))
        assertEquals(listOf("抛物线"), titles)
        release.complete(Unit)
        tools.awaitRenders()
        assertEquals(listOf("抛物线", "修订"), titles)
        assertEquals("抛物线", (tools.figures.single() as ReplyFigure.Plot).spec.title)
    }

    @Test fun failedRendersCanRetryAndMixedSlotsKeepTheirIds() = runTest {
        var succeed = false
        val tools = FigureTools(this) { if (!succeed) throw IllegalStateException("render failed") else true }
        val curve = FigureToolsTest.args(2, "plot", FigureToolsTest.CURVE)
        val first = tools.execute("render_figure", curve)
        tools.awaitRenders()
        assertEquals(listOf(ReplyFigure.Missing, ReplyFigure.Missing), tools.figures)
        succeed = true
        assertEquals("Accepted output must not depend on scheduling", first, tools.execute("render_figure", curve))
        tools.execute("render_figure", FigureToolsTest.args(1, "diagram", FigureToolsTest.DIAGRAM))
        tools.awaitRenders()
        assertTrue(tools.figures[0] is ReplyFigure.Diagram)
        assertTrue(tools.figures[1] is ReplyFigure.Plot)
    }

    @Test fun cancellingTheSolveCancelsPendingRenders() = runTest {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val solve = launch {
            coroutineScope {
                val tools = FigureTools(this) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                }
                tools.execute("render_figure", FigureToolsTest.args(1, "plot", FigureToolsTest.CURVE))
                tools.awaitRenders()
            }
        }
        started.await()
        solve.cancelAndJoin()
        assertTrue(stopped.isCompleted)
    }
}
