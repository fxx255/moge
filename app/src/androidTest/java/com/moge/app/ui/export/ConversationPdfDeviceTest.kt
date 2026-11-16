package com.moge.app.ui.export

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.moge.app.ui.markdown.FigurePathResolver
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConversationPdfDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = FigurePathResolver { path, _ -> path.takeIf { File(it).isFile } }
    @Before fun setup() { PDFBoxResourceLoader.init(context) }

    @Test fun singleA4PdfKeepsConversationsParagraphsMathTablesAndFigures(): Unit = runBlocking {
        val figure = File(context.cacheDir, "pdf-figure.png")
        val bitmap = Bitmap.createBitmap(700, 240, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            Canvas(bitmap).drawLine(20f, 200f, 680f, 20f, Paint().apply { color = android.graphics.Color.BLUE; strokeWidth = 4f })
            figure.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        val sections = listOf(
            ConversationPdfSection("信号与系统 · FIRST_START", "通信原理 · 2026.11.16", listOf(
                ExportPart.Text("提问 1", markdown = false, heading = true),
                ExportPart.Text("计算傅里叶变换，并画出频谱。"),
                ExportPart.Text("回答 1", markdown = false, heading = true),
                ExportPart.Text("**频域表达式**\n\n\$\$X(f)=\\int_{-\\infty}^{\\infty}x(t)e^{-j2\\pi ft}\\,dt\$\$\n\n- 周期信号具有离散谱线。\n- 频谱与时域信号互相对应。"),
                ExportPart.Text("| 参数 | 含义 |\n| --- | --- |\n| \$f\$ | 频率 |\n| \$T_s\$ | 采样周期 |"),
                ExportPart.Image(figure.path, "频谱示意", true),
            ) + (1..65).map { ExportPart.Text("Paragraph $it. This content must be retained across page boundaries. END_$it") }),
            ConversationPdfSection("英语写作 · SECOND_START", "英语 · 2026.11.10", listOf(
                ExportPart.Text("提问 1", markdown = false, heading = true),
                ExportPart.Text("请优化结尾，并保留正常换行。"),
                ExportPart.Text("\$\$\\text{I would appreciate it if these practical, low-cost measures could be piloted next semester.}\$\$"),
                ExportPart.Text("SECOND_END"),
            )),
        )
        val progress = mutableListOf<String>()
        val result = renderConversationPdf(context, sections.size, "samples.pdf", resolver, section = { sections[it] }, onProgress = progress::add)
        try {
            assertTrue(result.file.readBytes().take(5).toByteArray().toString(Charsets.US_ASCII).startsWith("%PDF-"))
            assertTrue(result.pages > 2)
            assertTrue(result.warnings.toString(), result.warnings.isEmpty())
            assertEquals(listOf(result.file.name), result.files.directory.listFiles()!!.map { it.name })
            val share = pdfShareIntent(context, result.file)
            assertEquals("application/pdf", share.type)
            @Suppress("DEPRECATION")
            val uri = share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals(uri, share.clipData!!.getItemAt(0).uri)
            context.contentResolver.openInputStream(uri)!!.use { input ->
                assertArrayEquals(result.file.readBytes(), input.readBytes())
            }
            PDDocument.load(result.file).use { pdf ->
                assertEquals(result.pages, pdf.numberOfPages)
                assertTrue(pdf.pages.all { it.mediaBox.width == 595f && it.mediaBox.height == 842f })
                val text = PDFTextStripper().getText(pdf)
                for (index in 1..65) assertTrue("Missing paragraph $index", text.contains("END_$index"))
                assertTrue(text.indexOf("FIRST_START") < text.indexOf("SECOND_START"))
                assertTrue(text.contains("SECOND_END"))
                val secondPages = (1..pdf.numberOfPages).filter { page -> PDFTextStripper().apply {
                    startPage = page; endPage = page
                }.getText(pdf).contains("SECOND_START") }
                assertEquals(1, secondPages.size)
            }
            run {
                val root = File(context.filesDir, "conversation-pdf-qa").apply { mkdirs() }
                result.file.copyTo(File(root, "conversations.pdf"), overwrite = true)
                ParcelFileDescriptor.open(result.file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { pdf ->
                        for (index in listOf(0, pdf.pageCount - 1)) pdf.openPage(index).use { page ->
                            val preview = Bitmap.createBitmap(893, 1263, Bitmap.Config.ARGB_8888)
                            try {
                                page.render(preview, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                File(root, "page-${index + 1}.png").outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            } finally { preview.recycle() }
                        }
                    }
                }
            }
        } finally { result.files.close(force = true); figure.delete() }
    }

    @Test fun cancelledPdfRemovesOnlyItsOwnTemporaryFiles(): Unit = runBlocking {
        val unrelated = File(context.cacheDir, "${ExportLimits.CACHE_DIRECTORY}/keep/keep.pdf").apply { parentFile!!.mkdirs(); writeText("keep") }
        val root = unrelated.parentFile!!.parentFile!!
        val before = root.listFiles()!!.map { it.name }.toSet()
        val job = launch {
            renderConversationPdf(context, 2, "cancelled.pdf", resolver, section = { index ->
                if (index == 1) { currentCoroutineContext().cancel(); yield() }
                ConversationPdfSection("one", "", listOf(ExportPart.Text("saved")))
            }, onProgress = {})
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(unrelated.isFile)
        assertEquals(before, root.listFiles()!!.map { it.name }.toSet())
    }
}
