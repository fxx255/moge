package com.moge.app.ui.solve

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.markdown.selectedMarkdownText
import com.moge.app.ui.theme.MogeTheme
import io.noties.markwon.image.AsyncDrawableScheduler
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathDrawable
import java.io.File
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h820dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FollowUpMathLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val integral = "\\frac{4}{T_s^2}\\int_0^{T_s}\\int_0^{T_s}" +
        "E\\left[n_w(t)n_w(\\tau)\\right]\\cos(2\\pi f_ct)\\cos(2\\pi f_c\\tau)dt\\,d\\tau"
    private val formula = "\\sigma^2=E[n^2]=$integral"
    private data class LayoutCase(val width: Int, val scale: Float, val appearance: Appearance, val mixed: Boolean)

    @Test fun `reported long follow ups stay inside the bubble through resizing font scaling and theme changes`() {
        val cases = listOf(
            LayoutCase(360, 1f, Appearance.PAPER, true),
            LayoutCase(240, 1.5f, Appearance.CHALK, false),
            LayoutCase(320, 1.5f, Appearance.PAPER, true),
            LayoutCase(220, 1f, Appearance.CHALK, false),
        )
        val current = mutableStateOf(cases.first())
        var background = 0
        compose.setContent {
            val density = LocalDensity.current.density
            val case = current.value
            CompositionLocalProvider(LocalDensity provides Density(density, case.scale)) {
                MogeTheme(case.appearance) {
                    background = MogeTheme.paper.tornNote.toArgb()
                    val source = if (case.mixed) "$$\n$formula\n$$\n\n这段解释一下" else "$$\n$integral\n$$"
                    Box(Modifier.width(case.width.dp)) {
                        FollowUpNote(SolveItem.Question("q", source, emptyList(), "", false)) { _, _ -> }
                    }
                }
            }
        }
        for (case in cases) {
            compose.runOnIdle { current.value = case }
            compose.onNodeWithContentDescription("复制题目原文").assertDoesNotExist()
            compose.runOnIdle {
                val root = compose.activity.findViewById<ViewGroup>(android.R.id.content)
                val view = nativeMathViews(root).single()
                val maximumWidth = ((minOf(case.width, 300) - 28) * view.resources.displayMetrics.density).roundToInt()
                assertTrue("Native content must use the bubble width", view.width in 1..maximumWidth)
                assertTrue(view.isTextSelectable)
                AsyncDrawableScheduler.unschedule(view)
                val text = view.text as Spanned
                val spans = nativeMathSpans(view)
                assertTrue(spans.isNotEmpty())
                val destinations = spans.map { it.drawable.destination.trim() }
                assertTrue("The integral's final differential must remain present", destinations.last().endsWith("d\\tau"))
                spans.forEach { span ->
                    val native = JLatexMathDrawable.builder(span.drawable.destination).textSize(view.textSize)
                        .color(view.currentTextColor).align(JLatexMathDrawable.ALIGN_CENTER).padding(0).build()
                    span.drawable.initWithKnownDimensions(view.width - view.totalPaddingLeft - view.totalPaddingRight, view.textSize)
                    span.drawable.setResult(native)
                    assertEquals(span.drawable.destination.trim(), selectedMarkdownText(text,
                        text.getSpanStart(span), text.getSpanEnd(span)))
                }
                val width = view.width
                view.text = text
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(view.left, view.top, view.left + width, view.top + view.measuredHeight)
                spans.forEach { span ->
                    val start = text.getSpanStart(span)
                    val left = view.totalPaddingLeft + view.layout.getPrimaryHorizontal(start)
                    val right = left + span.drawable.bounds.width()
                    assertTrue("Formula must fit inside the bubble: $right / $width", right <= width - view.totalPaddingRight + 1)
                    assertTrue(left >= view.totalPaddingLeft)
                }
                val bitmap = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(background)
                    view.draw(canvas)
                    val pixels = IntArray(width * view.height)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, view.height)
                    assertTrue("Formula must draw visible ink in both themes", pixels.any { it != background })
                    val file = File("build/ui-refinement-previews/follow-up-${case.width}-${case.scale}-${case.appearance}.png")
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
            compose.waitForIdle()
            compose.runOnIdle {
                val surface = compose.activity.findViewById<ViewGroup>(android.R.id.content).rootView
                val note = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
                try {
                    surface.draw(Canvas(note))
                    val file = File("build/ui-refinement-previews/torn-note-${case.width}-${case.scale}-${case.appearance}.png")
                    file.outputStream().use { note.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { note.recycle() }
            }
        }
    }
}
