package com.moge.app.ui.export

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Spanned
import com.moge.app.core.LatexEngine
import com.moge.app.ui.markdown.BaselineLatexSpan
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExponentialFormulaExportTest {
    @Test fun savedExponentialExportsIdenticallyToTheCorrectFormula() {
        val context = RuntimeEnvironment.getApplication()
        LatexEngine.init(context)
        val formula = """e^{-j2\pi n t/T_s}=e^{-j2\pi n(u+kT_s)/T_s}=e^{-j2\pi n u/T_s}e^{-j2\pi nk}=e^{-j2\pi n u/T_s},"""
        val correct = "指数部分则变为\n\n$$\n$formula\n$$\n\n" +
            "最后一步用到了 \$\$n,k\$\$ 都是整数，所以 \$\$e^{-j2\\pi nk}=1\$\$。"
        val saved = correct.replace("$$\n$formula", "$$\\n$formula")
        val images = mutableListOf<Bitmap>()
        try {
            for (source in listOf(correct, saved)) {
                val warnings = mutableListOf<String>()
                val view = exportTextView(context, source, ExportLimits.WIDTH, warning = { warnings += it })
                val text = view.text as Spanned
                val formulas = text.getSpans(0, text.length, BaselineLatexSpan::class.java).sortedBy(text::getSpanStart)
                assertTrue(formulas.size >= 3)
                assertTrue(formulas.first().drawable.destination.contains("e^{-j2\\pi n t/T_s}"))
                assertTrue(formulas.all { it.drawable.hasResult() && it.drawable.bounds.height() > 0 })
                assertTrue(formulas.none { it.drawable.destination.contains("\\ne") })
                assertTrue(warnings.toString(), warnings.isEmpty())
                assertFalse(text.contains('$'))
                val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                images += image
                image.eraseColor(EXPORT_PAPER)
                view.draw(Canvas(image))
            }
            assertTrue("Saved answer must draw the same pixels as the correct exponential", images[0].sameAs(images[1]))
            if (System.getenv("FORMULA_EXPORT_SAMPLES") == "1") {
                val sample = File("build/formula-samples/exponential-paper.png").apply { parentFile!!.mkdirs() }
                sample.outputStream().use { images[1].compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally { images.forEach(Bitmap::recycle) }
    }
}
