package com.moge.app.data.parse

import com.moge.app.data.parse.ReplyParser
import org.junit.Assert.*
import org.junit.Test

class PlotStylesAndShadingTest {
    @Test fun `styles colors and point shapes survive parsing with bounded values`() {
        val plot = ReplyParser.parse("""{"reply":"图", "plots":[{"series":[
            {"expr":"x","style":"dashdot","colorIndex":11,"color":"#4DDDE0","width":999},
            {"points":[[0,0]],"style":"line_marker","markerShape":"diamond","markerSize":4},
            {"expr":"x^2","style":"dotted","color":"red"}]}]}""").plots.single()
        assertEquals("dashdot", plot.series[0].style)
        assertEquals(11, plot.series[0].colorIndex)
        assertEquals("#4DDDE0", plot.series[0].color)
        assertEquals(5f, plot.series[0].width)
        assertEquals(1, plot.series[1].colorIndex)
        assertEquals("diamond", plot.series[1].markerShape)
        assertEquals(4f, plot.series[1].markerSize)
        assertEquals(2, plot.series[2].colorIndex)
        assertNull(plot.series[2].color)
    }

    @Test fun `bad shade does not discard valid curves or other regions`() {
        val reply = ReplyParser.parse("""{"reply":"正文", "plots":[{"series":[{"expr":"x^2"}],
            "shades":[{"x0":0,"x1":1,"upper":"x^2","lower":"x","pattern":"hatched","opacity":0.2},
            {"x0":0,"x1":1,"upper":"execute(x)"},
            {"x0":1,"x1":0,"upper":"x"},
            {"points":[[0,0],[1,0],[0,1]],"pattern":"crosshatch","colorIndex":8}],
            "markAreas":[{"x0":0,"x1":2,"y0":1,"y1":3}]}]}""")
        val plot = reply.plots.single()
        assertEquals(2, plot.shades.size)
        assertEquals("x", plot.shades.first().lower)
        assertEquals("hatched", plot.shades.first().pattern)
        assertEquals(3, plot.shades.last().points!!.size)
        assertEquals(1.0, plot.markAreas.single().y0!!, 0.0)
        assertEquals(2, reply.warnings.size)
        assertEquals("正文", reply.reply)
    }
}
