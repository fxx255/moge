package com.moge.app.domain.diagram

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.domain.coding.CodingSpec
import com.moge.app.domain.coding.ConvolutionalTrellis
import com.moge.app.domain.coding.TrellisSpec
import com.moge.app.ui.figure.DiagramRenderer
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrellisDiagramDeviceTest {
    @Test fun rendersAllReferenceModesWithComputedTransitionsOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val code = CodingSpec(memory = 2, generators = listOf(listOf(0, 1, 2), listOf(0, 2)))
        for (mode in listOf("outputs", "distances", "survivors")) {
            val options = TrellisSpec(mode = mode,
                receivedBits = if (mode == "outputs") "" else "01011001001011",
                inputBits = if (mode == "outputs") "10111" else "")
            val graph = ConvolutionalTrellis.build(code, options)
            assertEquals(setOf(0), graph.layers.last())
            assertEquals(7, graph.highlightedPath.size)
            if (mode != "outputs") assertEquals(graph.bestDistance, graph.highlightedPath.sumOf { it.distance!! })
            val bitmap = DiagramRenderer.render(DiagramSpec("", profile = DiagramLayoutProfile.CONVOLUTIONAL_TRELLIS,
                coding = code, trellis = options))
            try {
                assertTrue(bitmap.width <= 4096 && bitmap.height <= 4096)
                File(context.getExternalFilesDir(null), "trellis-device-$mode.png").outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }
}
