package com.moge.app.ui.export

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.text.Spanned
import com.moge.app.ui.markdown.FigurePathResolver
import io.noties.markwon.image.AsyncDrawableSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathAndroid
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnswerExportRendererTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val resolver = FigurePathResolver { path, _ -> path.takeIf { File(it).isFile } }

    @Before fun prepare() {
        Dispatchers.setMain(StandardTestDispatcher())
        JLatexMathAndroid.init(context)
    }
    @After fun reset() { Dispatchers.resetMain() }

    @Test fun `inline formula is loaded before measurement and produces pixels`() {
        val view = exportTextView(context, "Before \$\$\\frac{1}{2}+x^2\$\$ after", 900)
        val text = view.text as Spanned
        val formulas = text.getSpans(0, text.length, AsyncDrawableSpan::class.java)
        assertTrue("Expected a native math span", formulas.isNotEmpty())
        assertTrue(formulas.all { it.drawable.hasResult() && it.drawable.bounds.height() > 0 })
        val image = Bitmap.createBitmap(900, view.height, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(EXPORT_PAPER)
            view.draw(Canvas(image))
            assertTrue("Native formula/text drawing is empty", inkPixels(image) > 80)
        } finally { image.recycle() }
        val onlyMath = exportTextView(context, "\$\$\\frac{1}{2}+x^2\$\$", 900)
        val formulaImage = Bitmap.createBitmap(900, onlyMath.height, Bitmap.Config.ARGB_8888)
        try {
            formulaImage.eraseColor(EXPORT_PAPER)
            onlyMath.draw(Canvas(formulaImage))
            assertTrue("The formula itself must draw, without prose contributing ink", inkPixels(formulaImage) > 80)
        } finally { formulaImage.recycle() }
    }

    @Test fun `failed formula preserves its complete source rather than truncated placeholder`() {
        val source = "\$\$\\commandThatDoesNotExist{" + "q".repeat(150) + "}\$\$"
        val warnings = mutableListOf<String>()
        val view = exportTextView(context, source, 900, warning = { warnings += it })
        assertTrue(view.text.toString().contains(source))
        assertTrue(warnings.isNotEmpty())
    }

    @Test fun `wide table and formula cells draw bounded pages without missing columns`() = runTest {
        val header = (1..7).joinToString("|", "|", "|") { "Column $it" }
        val divider = (1..7).joinToString("|", "|", "|") { "---" }
        val row = (1..7).joinToString("|", "|", "|") { "value$it \$\$x^$it\$\$" }
        val result = renderAnswerExport(context, AnswerExportContent("Table", "Question", answerText = "$header\n$divider\n$row"),
            ExportChoice.FULL, resolver) { }
        try {
            assertTrue(result.warnings.isEmpty())
            result.pages.forEach { path ->
                val bitmap = BitmapFactory.decodeFile(path.absolutePath)!!
                try {
                    assertEquals(1080, bitmap.width)
                    assertTrue(bitmap.height in 1..ExportLimits.HEIGHT)
                    assertTrue("short exports crop trailing blank paper", bitmap.height < ExportLimits.HEIGHT)
                    assertEquals(EXPORT_PAPER, bitmap.getPixel(0, 0))
                    assertTrue(inkPixels(bitmap) > 200)
                } finally { bitmap.recycle() }
            }
            System.getProperty("moge.export.qaDirectory")?.let { directory ->
                val output = File(directory).apply { mkdirs() }
                result.pages.first().copyTo(File(output, "table-formulas.png"), overwrite = true)
            }
        } finally { result.files.close(force = true) }
    }

    @Test fun `long source splits into sequential images and missing figure is visible`() = runTest {
        val body = (1..350).joinToString("\n\n") { "Paragraph $it: " + "bounded native text ".repeat(8) }
        var requestedDark: Boolean? = null
        val result = renderAnswerExport(context, AnswerExportContent("Long", "Selected question", answerText = body,
            figurePaths = listOf("missing")), ExportChoice.FULL, FigurePathResolver { _, dark -> requestedDark = dark; null }) { }
        try {
            assertTrue(result.pages.size > 1)
            assertEquals(false, requestedDark)
            assertTrue(result.warnings.any { "生成图 1缺失" in it })
            assertEquals(result.pages.indices.map { "page_${(it + 1).toString().padStart(4, '0')}.png" }, result.pages.map { it.name })
            assertTrue(result.files.directory.listFiles()!!.none { it.extension == "partial" })
        } finally { result.files.close(force = true) }
    }

    @Test fun `cancellation at encoding removes partial and already completed pages`() = runTest {
        val root = File(context.cacheDir, ExportLimits.CACHE_DIRECTORY)
        val before = root.list()?.toSet().orEmpty()
        lateinit var job: Job
        job = launch(start = CoroutineStart.LAZY) {
            renderAnswerExport(context, AnswerExportContent("Cancel", "Question", answerText = "x\n\n".repeat(15000)),
                ExportChoice.FULL, resolver) { job.cancel() }
        }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(before, root.list()?.toSet().orEmpty())
    }

    @Test fun `export uses app paper ink headings grid and red margin rather than unrelated colors`() = runTest {
        val heading = exportTextView(context, "两张图片的积分题", 900, markdown = false, heading = true)
        assertEquals(EXPORT_TITLE, heading.currentTextColor)
        assertEquals(EXPORT_INK, exportTextView(context, "Body", 900).currentTextColor)
        val photos = listOf(
            "题目：计算定积分 \$\$\\int_0^1 x^2 dx\$\$。",
            "补充条件：积分区间为 [0, 1]，请写出推导过程。",
        ).mapIndexed { index, source ->
            val view = exportTextView(context, source, 850)
            val photo = Bitmap.createBitmap(900, view.height + 48, Bitmap.Config.ARGB_8888)
            val file = File(context.cacheDir, "paper-sample-question-$index.png")
            try {
                photo.eraseColor(android.graphics.Color.WHITE)
                val canvas = Canvas(photo)
                canvas.translate(24f, 24f)
                view.draw(canvas)
                file.outputStream().use { photo.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { photo.recycle() }
            file.path
        }
        val result = renderAnswerExport(context,
            AnswerExportContent("两张图片的积分题", "请根据题目图片完成积分计算。", questionPhotos = photos,
                answerText = "利用幂函数积分公式，先求原函数，再代入上下限：\n\n\$\$\\int_0^1 x^2 dx=\\left[\\frac{x^3}{3}\\right]_0^1=\\frac{1}{3}\$\$", finalAnswer = "答案：1/3"),
            ExportChoice.FULL, resolver) { }
        try {
            val bitmap = BitmapFactory.decodeFile(result.pages.first().path)!!
            try {
                assertEquals(EXPORT_PAPER, bitmap.getPixel(0, 0))
                assertEquals(EXPORT_MARGIN, bitmap.getPixel(ExportLimits.MARGIN - 28, 100))
                assertNotEquals("The paper must carry a subtle grid", EXPORT_PAPER, bitmap.getPixel(48, 40))
            } finally { bitmap.recycle() }
            System.getProperty("moge.export.qaDirectory")?.let { directory ->
                val output = File(directory).apply { mkdirs() }
                result.pages.first().copyTo(File(output, "paper-share.png"), overwrite = true)
            }
        } finally { result.files.close(force = true) }
    }

    private fun inkPixels(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { it != EXPORT_PAPER && it ushr 24 != 0 }
    }
}
