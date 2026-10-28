package com.moge.app.data.llm.tools

import android.app.Application
import com.moge.app.data.parse.ReplyFigure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FigureToolsTest {
    private val rendered = mutableListOf<ReplyFigure>()
    private val tools = FigureTools { rendered += it; true }

    @Test fun mixedFigureTypesKeepReservedIdsAcrossValidationFailureAndRepair() = runBlocking {
        assertEquals("ok", status(tools.execute("render_figure", args(1, "diagram", DIAGRAM))))
        assertEquals("error", status(tools.execute("render_figure", args(2, "plot", """{"series":[{"expr":"unknown(x)"}]}"""))))
        assertTrue(tools.figures[0] is ReplyFigure.Diagram)
        assertEquals(ReplyFigure.Missing, tools.figures[1])
        assertEquals("ok", status(tools.execute("render_figure", args(2, "plot", CURVE))))
        assertTrue(tools.figures[0] is ReplyFigure.Diagram)
        assertTrue(tools.figures[1] is ReplyFigure.Plot)
        assertEquals(2, rendered.size)
    }

    @Test fun duplicateCallsReuseFigureAndBadRepairCannotReplaceLastSuccessfulSpec() = runBlocking {
        val first = tools.execute("render_figure", args(1, "plot", CURVE))
        assertEquals(first, tools.execute("render_figure", args(1, "plot", CURVE)))
        val original = tools.figures.single()
        assertEquals("error", status(tools.execute("render_figure", args(1, "plot", """{"series":[]}"""))))
        assertEquals(original, tools.figures.single())
        assertEquals(1, rendered.size)
        assertTrue(tools.execute("render_figure", args(1, "plot", CURVE)).contains("3次"))
    }

    @Test fun failedRenderingReservesSlotAndNeverClaimsSuccess() = runBlocking {
        val failed = FigureTools { false }
        assertEquals("error", status(failed.execute("render_figure", args(3, "plot", CURVE))))
        assertEquals(List(3) { ReplyFigure.Missing }, failed.figures)
        assertEquals("error", status(failed.execute("render_figure", args(5, "plot", CURVE))))
        assertEquals(3, failed.figures.size)
    }

    @Test fun cancellationPropagatesWithoutPublishingFigure() = runBlocking {
        val cancelled = FigureTools { throw CancellationException("cancel") }
        try { cancelled.execute("render_figure", args(1, "plot", CURVE)); fail("cancelled") }
        catch (_: CancellationException) { }
        assertEquals(listOf(ReplyFigure.Missing), cancelled.figures)
    }

    @Test fun specializedGuidesAreIsolatedAndStillDescribeRealRendererContracts() {
        val curve = FigureToolCatalog.guide("curve")
        assertFalse(curve.contains("radiusMax")); assertFalse(curve.contains("voronoi"))
        assertTrue(curve.contains("tickLabels")); assertTrue(curve.contains("shades"))
        assertTrue(FigureToolCatalog.guide("polar").contains("thetaMin"))
        assertTrue(FigureToolCatalog.guide("constellation").contains("allRegions"))
        assertTrue(FigureToolCatalog.guide("diagram").contains("iq_demodulator"))
        assertFalse(FigureToolCatalog.guide("diagram").contains("generatorExponents"))
        assertTrue(FigureToolCatalog.guide("coding").contains("generatorExponents"))
        assertFalse(FigureToolCatalog.guide("coding").contains("iq_demodulator"))
    }

    @Test fun repeatedGuideUsesPriorTranscriptButDifferentKindsStillReturnTheirOwnContract() = runBlocking {
        val first = tools.execute("get_figure_guide", buildJsonObject { put("kind", "curve") })
        val repeated = tools.execute("get_figure_guide", buildJsonObject { put("kind", "curve") })
        assertEquals(FigureToolCatalog.guide("curve"), first)
        assertTrue(repeated.contains("已在本轮前文提供"))
        assertTrue(repeated.length < first.length / 10)
        assertEquals(FigureToolCatalog.guide("coding"), tools.execute("get_figure_guide", buildJsonObject { put("kind", "coding") }))
        repeat(2) { assertTrue(tools.execute("get_figure_guide", buildJsonObject { put("kind", "unknown") }).contains("error")) }
    }

    @Test fun polarAndComputedCodingTemplatesUseExistingValidators() = runBlocking {
        assertEquals("ok", status(tools.execute("render_figure", args(1, "plot",
            """{"polar":{"radiusMax":2.2},"series":[{"expr":"1-cos(theta)"}]}"""))))
        assertEquals("ok", status(tools.execute("render_figure", args(2, "diagram",
            """{"title":"卷积码","profile":"convolutional_state_graph","coding":{"version":1,"memory":2,"generators":[[0,1,2],[0,2]]}}"""))))
        assertEquals("error", status(tools.execute("render_figure", args(3, "diagram",
            """{"profile":"convolutional_encoder","coding":{"version":1,"memory":2}}"""))))
    }

    private fun status(text: String) = Json.parseToJsonElement(text).jsonObject["status"]!!.jsonPrimitive.content
    companion object {
        const val CURVE = """{"title":"抛物线","x":{"min":-2,"max":2},"series":[{"expr":"x^2"}]}"""
        const val DIAGRAM = """{"title":"流程","nodes":[{"id":"a","label":"输入"},{"id":"b","label":"输出"}],"edges":[{"from":"a","to":"b"}]}"""
        fun args(id: Int, type: String, spec: String) = buildJsonObject {
            put("figure_id", id); put("type", type); put("spec", Json.parseToJsonElement(spec))
        }
    }
}
