package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.moge.app.core.LatexEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathDrawable

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoxedFormulaRenderTest {
    @Test fun `boxed answers keep solid glyphs through repeated drawing in both themes`() {
        LatexEngine.init(RuntimeEnvironment.getApplication())
        for (ink in listOf(Color.BLACK, Color.WHITE)) {
            for (body in listOf("01101001", """-816\Delta=-2.390625\,\mathrm{V}""", """\mathbf{816}\Delta""")) {
                val plain = JLatexMathDrawable.builder(body).textSize(64f).color(ink).build()
                val framed = JLatexMathDrawable.builder("\\boxed{$body}").textSize(64f).color(ink).build()
                val reference = bitmap(plain)
                try {
                    val expected = alphaIn(reference, 0, 0, reference.width, reference.height)
                    repeat(2) {
                        val actual = bitmap(framed)
                        try {
                            val dx = (actual.width - reference.width) / 2
                            val dy = (actual.height - reference.height) / 2
                            val filled = alphaIn(actual, dx, dy, reference.width, reference.height)
                            assertTrue("Frame must not turn $body into outlined glyphs: $filled / $expected",
                                filled > expected * .88)
                        } finally { actual.recycle() }
                    }
                } finally { reference.recycle() }
            }
        }
    }

    private fun bitmap(drawable: JLatexMathDrawable): Bitmap = Bitmap.createBitmap(
        drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888,
    ).also {
        drawable.setBounds(0, 0, it.width, it.height)
        drawable.draw(Canvas(it))
    }

    private fun alphaIn(bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int): Long {
        var alpha = 0L
        for (y in top until top + height) for (x in left until left + width) alpha += Color.alpha(bitmap.getPixel(x, y))
        return alpha
    }
}
