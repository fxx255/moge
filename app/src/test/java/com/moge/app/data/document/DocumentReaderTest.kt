package com.moge.app.data.document

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.moge.app.runtime.DraftStore
import com.moge.app.runtime.PosixAtomicFileShadow
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [PosixAtomicFileShadow::class])
class DocumentReaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun zip(extension: String, entries: Map<String, String>): File =
        File.createTempFile("document", ".$extension", context.cacheDir).apply {
            ZipOutputStream(outputStream()).use { output ->
                entries.forEach { (name, content) ->
                    output.putNextEntry(ZipEntry(name)); output.write(content.toByteArray()); output.closeEntry()
                }
            }
        }

    @Test fun docxKeepsHeadingTableChineseAndImageIdentity() {
        val file = zip("docx", mapOf(
            "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>第三章</w:t></w:r></w:p>
                <w:p><w:r><w:t>中文图文讲义</w:t></w:r></w:p>
                <w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>合并单元格</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>数值 12</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>""",
            "word/media/image1.png" to "placeholder",
        ))
        val index = DocumentReader.office(file)
        assertEquals("第三章", index.sections.first().title)
        assertTrue(index.sections.first().text.contains("中文图文讲义"))
        assertTrue(index.sections.first().text.contains("合并单元格 [跨 2 列] | 数值 12"))
        assertEquals(listOf("word/media/image1.png"), index.images)
    }

    @Test fun pptxUsesPresentationRelationshipsRatherThanZipOrder() {
        val file = zip("pptx", linkedMapOf(
            "ppt/slides/slide2.xml" to """<p:sld xmlns:p="urn:p" xmlns:a="urn:a"><a:p><a:r><a:t>实际第二页</a:t></a:r></a:p></p:sld>""",
            "ppt/slides/slide10.xml" to """<p:sld xmlns:p="urn:p" xmlns:a="urn:a"><a:p><a:r><a:t>实际第一页</a:t></a:r></a:p></p:sld>""",
            "ppt/presentation.xml" to """<p:presentation xmlns:p="urn:p" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId r:id="first"/><p:sldId r:id="second"/></p:sldIdLst></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships><Relationship Id="first" Target="slides/slide10.xml"/><Relationship Id="second" Target="slides/slide2.xml"/></Relationships>""",
        ))
        assertEquals(listOf("实际第一页", "实际第二页"), DocumentReader.office(file).sections.map { it.text.trim() })
    }

    @Test fun compressedPdfCanReadPageThirteen() {
        PDFBoxResourceLoader.init(context)
        val file = File.createTempFile("long", ".pdf", context.cacheDir)
        PDDocument().use { document ->
            repeat(13) { number ->
                val page = PDPage(); document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(30f, 700f); stream.showText("page " + (number + 1)); stream.endText()
                }
            }
            document.save(file)
        }
        assertTrue(DocumentReader.read(context, file, "page:13").text.contains("page 13"))
    }

    @Test fun originalAndDocumentOnlyDraftSurviveSourceDeletionAndRestart() = runBlocking {
        val source = File(context.cacheDir, "原始文件名.txt").apply { writeText("中文附件内容") }
        val store = DocumentStore(context)
        val path = store.import(Uri.fromFile(source))
        source.delete()
        val saved = DocumentStore(context).attachment(path)
        assertEquals("原始文件名.txt", saved.name)
        assertEquals("中文附件内容", File(path).readText())
        val drafts = DraftStore(context)
        drafts.persist(drafts.reserveSave("documents-only", "", emptyList(), listOf(path)))
        val restored = DraftStore(context).load("documents-only")!!
        assertEquals("", restored.text)
        assertEquals(emptyList<String>(), restored.photoPaths)
        assertEquals(listOf(path), restored.documentPaths)
        assertFalse(drafts.clearIfBaselineMatches("documents-only", "", emptyList()))
        assertTrue(path in drafts.referencedPhotoPaths())
    }

    @Test fun toolScopeAndContinuationOffsetsAreExplicit() = runBlocking {
        val source = File(context.cacheDir, "long.txt").apply { writeText("中".repeat(25_000)) }
        val store = DocumentStore(context)
        val attachment = store.attachment(store.import(Uri.fromFile(source)))
        val tools = DocumentTools(context, listOf(attachment))
        val denied = tools.execute("read_document", buildJsonObject { put("document_id", "not-attached"); put("locator", "section:1") }, true)
        assertTrue(denied.isError)
        val result = tools.execute("inspect_document", buildJsonObject { put("document_id", attachment.id) }, true)
        assertEquals(4, Json.parseToJsonElement(result.text).jsonObject["total_sections"]!!.jsonPrimitive.int)
    }

    @Test fun externalXmlEntitiesAreRejected() {
        val file = zip("docx", mapOf("word/document.xml" to """<!DOCTYPE document [<!ENTITY entity SYSTEM "file:///never-read">]><document>&entity;</document>"""))
        assertThrows(Exception::class.java) { DocumentReader.office(file) }
    }

    private fun unsupportedFeaturesFactory() = object : DocumentBuilderFactory() {
        override fun setAttribute(name: String, value: Any?) { throw IllegalArgumentException(name) }
        override fun getAttribute(name: String): Any = throw IllegalArgumentException(name)
        override fun setFeature(name: String, value: Boolean) { throw ParserConfigurationException(name) }
        override fun getFeature(name: String): Boolean = throw ParserConfigurationException(name)
        override fun newDocumentBuilder(): javax.xml.parsers.DocumentBuilder {
            val namespaces = isNamespaceAware
            return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = namespaces }.newDocumentBuilder()
        }
    }

    @Test fun officeXmlReadsChineseWhenParserDoesNotSupportDesktopFeatures() {
        val xml = """<?xml version="1.0"?><w:document xmlns:w="urn:word"><w:body>中文正文</w:body></w:document>"""
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val parsed = DocumentReader.parseOfficeXml(xml.toByteArray(charset), unsupportedFeaturesFactory())
            assertEquals("中文正文", parsed.getElementsByTagNameNS("urn:word", "body").item(0).textContent)
        }
    }

    @Test fun dtdIsRejectedEvenWhenParserDoesNotSupportSecurityFeatures() {
        val xml = """<!DOCTYPE document [<!ENTITY entity SYSTEM "file:///never-read">]><document>&entity;</document>"""
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            assertThrows(IllegalArgumentException::class.java) {
                DocumentReader.parseOfficeXml(xml.toByteArray(charset), unsupportedFeaturesFactory())
            }
        }
    }

    @Test fun docxAssociatesImagesAndPreservesFractionAndExponent() {
        val file = zip("docx", mapOf(
            "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math" xmlns:a="urn:a" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body><w:p><w:r><w:t>公式图</w:t></w:r><m:oMath><m:f><m:num><m:r><m:t>1</m:t></m:r></m:num><m:den><m:sSup><m:e><m:r><m:t>x</m:t></m:r></m:e><m:sup><m:r><m:t>2</m:t></m:r></m:sup></m:sSup></m:den></m:f></m:oMath><a:blip r:embed="picture"/></w:p></w:body></w:document>""",
            "word/_rels/document.xml.rels" to """<Relationships><Relationship Id="picture" Target="media/image1.png"/></Relationships>""",
            "word/media/image1.png" to "placeholder",
        ))
        val value = DocumentReader.office(file).sections.single().text
        assertTrue(value.contains("\\frac{1}{{x}^{2}}"))
        assertTrue(value.contains("图片：word/media/image1.png"))
    }

    @Test fun searchResultsCountAsAccessedSourcesAndHashDetectsSameLengthChanges() = runBlocking {
        val source = File(context.cacheDir, "search.txt").apply { writeText("原始内容") }
        val store = DocumentStore(context)
        val attachment = store.attachment(store.import(Uri.fromFile(source)))
        val tools = DocumentTools(context, listOf(attachment))
        val result = tools.execute("search_document", buildJsonObject { put("document_id", attachment.id); put("query", "原始") }, false)
        assertFalse(result.isError)
        assertEquals(setOf(attachment.id to "section:1"), tools.sources)
        File(attachment.path).writeText("修改内容")
        assertThrows(IllegalArgumentException::class.java) { store.attachment(attachment.path, verifyHash = true) }
        Unit
    }
}
