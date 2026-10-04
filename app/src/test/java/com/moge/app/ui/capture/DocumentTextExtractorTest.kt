package com.moge.app.ui.capture

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTextExtractorTest {
    @Test fun `docx and pptx xml text is extracted in archive order`() {
        val docx = temp("docx")
        ZipOutputStream(docx.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<w:document><w:t>第一题</w:t><w:t>答案</w:t></w:document>".toByteArray())
            zip.closeEntry()
        }
        val text = DocumentTextExtractor.extract(docx)
        assertTrue(text.contains("第一题"))
        assertTrue(text.contains("答案"))

        val pptx = temp("pptx")
        ZipOutputStream(pptx.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ppt/slides/slide1.xml"))
            zip.write("<p:sld><a:t>第一页</a:t></p:sld>".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        assertTrue(DocumentTextExtractor.extract(pptx).contains("第一页"))
    }

    @Test fun `plain pdf text operators are kept when available`() {
        val pdf = temp("pdf")
        pdf.writeText("BT (Question 1) Tj ET", StandardCharsets.ISO_8859_1)
        assertTrue(DocumentTextExtractor.extract(pdf).contains("Question"))
    }

    private fun temp(extension: String): File =
        File.createTempFile("moge-document-", ".$extension").also { it.deleteOnExit() }
}
