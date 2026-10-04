package com.moge.app.data.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.StringReader

data class DocumentSection(val id: String, val title: String, val text: String)
data class DocumentTable(val id: String, val title: String, val source: String, val text: String)
data class DocumentIndex(val sections: List<DocumentSection>, val images: List<String>, val note: String = "", val tables: List<DocumentTable> = emptyList())

/** Pages and OOXML structures, never a regex over binary PDF/Office data. */
object DocumentReader {
    private const val MAX_XML_BYTES = 12 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 100L * 1024 * 1024

    fun index(context: Context, file: File): DocumentIndex = when (file.extension.lowercase()) {
        "pdf" -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                DocumentIndex((1..renderer.pageCount).map { DocumentSection("page:$it", "第 $it 页", "") }, emptyList(),
                    "PDF 可按页读取文字或查看页面。扫描页需调用页面图像工具。")
            }
        }
        "docx", "pptx", "xlsx" -> office(file)
        "txt", "md", "csv" -> {
            val text = file.readText()
            DocumentIndex(text.chunked(8_000).mapIndexed { index, chunk ->
                DocumentSection("section:${index + 1}", "第 ${index + 1} 段", chunk)
            }, emptyList())
        }
        else -> DocumentIndex(emptyList(), emptyList(), "旧版 Office 需要转换为 DOCX/PPTX/PDF，原件已保留。")
    }

    fun read(context: Context, file: File, locator: String): DocumentSection {
        if (file.extension.equals("pdf", true)) {
            val page = locator.removePrefix("page:").toIntOrNull() ?: error("请提供 page:页码")
            PDFBoxResourceLoader.init(context)
            return PDDocument.load(file).use { document ->
                require(page in 1..document.numberOfPages) { "页码超出范围" }
                val text = PDFTextStripper().apply { startPage = page; endPage = page; sortByPosition = true }.getText(document)
                DocumentSection("page:$page", "第 $page 页", text.ifBlank { "此页没有可读文字层，请调用 view_document_image 查看页面。" })
            }
        }
        val value = index(context, file)
        value.tables.firstOrNull { it.id == locator }?.let { return DocumentSection(it.id, it.title + "（" + it.source + "）", it.text) }
        return value.sections.firstOrNull { it.id == locator } ?: error("未找到该章节、表格或幻灯片")
    }

    fun image(context: Context, file: File, locator: String): String {
        if (file.extension.equals("pdf", true)) {
            val pageIndex = (locator.removePrefix("page:").toIntOrNull() ?: error("请提供 page:页码")) - 1
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    require(pageIndex in 0 until renderer.pageCount) { "页码超出范围" }
                    renderer.openPage(pageIndex).use { page ->
                        val ratio = page.height.toDouble() / page.width
                        val width = minOf(1600, kotlin.math.sqrt(4_000_000.0 / ratio).toInt()).coerceAtLeast(1)
                        val height = (width * ratio).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val output = ByteArrayOutputStream()
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output))
                            "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                        } finally { bitmap.recycle() }
                    }
                }
            }
        }
        val images = index(context, file).images
        require(locator in images) { "图片不属于当前文档" }
        return ZipFile(file).use { zip ->
            val bytes = bytes(zip, locator)
            val mime = when (locator.substringAfterLast('.').lowercase()) {
                "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"; "webp" -> "image/webp"
                else -> error("这类嵌入图像需要转换为 PNG 或 PDF 页面")
            }
            "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    fun office(file: File): DocumentIndex = ZipFile(file).use { zip ->
        val entries = zip.entries().asSequence().toList()
        require(entries.size <= 20_000) { "Office 文件条目过多" }
        require(entries.sumOf { it.size.coerceAtLeast(0) } <= MAX_EXPANDED_BYTES) { "Office 文件解压后过大" }
        val images = entries.filter { !it.isDirectory && it.name.matches(Regex("(word|ppt|xl)/media/[^/]+")) }.map { it.name }
        when (file.extension.lowercase()) {
            "docx" -> {
                val document = xml(zip, "word/document.xml")
                val body = document.getElementsByTagNameNS("*", "body").item(0) as? Element ?: error("Word 缺少正文")
                val sections = mutableListOf<DocumentSection>()
                val tables = mutableListOf<DocumentTable>()
                val relations = relationships(zip, "word/document.xml")
                var title = "正文"
                val current = StringBuilder()
                fun flush() {
                    if (current.isNotBlank()) {
                        sections += DocumentSection("section:${sections.size + 1}", title, current.toString())
                        current.clear()
                    }
                }
                children(body).forEach { element ->
                    if (element.localName == "p") {
                        val style = element.getElementsByTagNameNS("*", "pStyle").item(0) as? Element
                        val styleName = style?.getAttributeNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "val").orEmpty()
                        val text = text(element)
                        if (styleName.contains("heading", true) || styleName.matches(Regex("[1-9]"))) {
                            flush(); title = text.ifBlank { "章节" }
                        }
                        current.appendLine(text)
                        val pictures = element.getElementsByTagNameNS("*", "blip")
                        for (i in 0 until pictures.length) {
                            val image = pictures.item(i) as Element
                            relations[image.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed")]
                                ?.takeIf { it in images }?.let { current.appendLine("图片：$it") }
                        }
                    } else if (element.localName == "tbl") {
                        val value = DocumentTable("table:${tables.size + 1}", "表格 ${tables.size + 1}", "section:${sections.size + 1}", table(element))
                        tables += value
                        current.appendLine("[${value.id}]").appendLine(value.text)
                    }
                    if (current.length >= 8_000) flush()
                }
                flush()
                listOf("word/footnotes.xml", "word/endnotes.xml").filter { zip.getEntry(it) != null }.forEach { path ->
                    sections += DocumentSection("section:${sections.size + 1}", "脚注与尾注", text(xml(zip, path).documentElement))
                }
                DocumentIndex(sections, images, "按章节定位；Word 的物理页码需要排版引擎。图片位置记录在所属章节中。", tables)
            }
            "pptx" -> {
                val documentTables = mutableListOf<DocumentTable>()
                val relationDocument = xml(zip, "ppt/_rels/presentation.xml.rels")
                val relations = relationDocument.getElementsByTagNameNS("*", "Relationship")
                val targets = (0 until relations.length).associate { index ->
                    val relation = relations.item(index) as Element
                    relation.getAttribute("Id") to resolvePart("ppt", relation.getAttribute("Target"))
                }
                val slideIds = xml(zip, "ppt/presentation.xml").getElementsByTagNameNS("*", "sldId")
                val sections = (0 until slideIds.length).map { index ->
                    val slide = slideIds.item(index) as Element
                    val id = slide.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                    val path = targets[id] ?: error("幻灯片关系缺失")
                    val content = xml(zip, path).documentElement
                    val slideText = buildString {
                        appendLine(text(content))
                        val tables = content.getElementsByTagNameNS("*", "tbl")
                        for (i in 0 until tables.length) {
                            val value = DocumentTable("table:${documentTables.size + 1}", "表格 ${documentTables.size + 1}", "slide:${index + 1}", table(tables.item(i) as Element))
                            documentTables += value
                            appendLine("[${value.id}]").appendLine(value.text)
                        }
                        val relPath = path.substringBeforeLast('/') + "/_rels/" + path.substringAfterLast('/') + ".rels"
                        if (zip.getEntry(relPath) != null) {
                            val rels = xml(zip, relPath).getElementsByTagNameNS("*", "Relationship")
                            for (i in 0 until rels.length) {
                                val relation = rels.item(i) as Element
                                val part = resolvePart(path.substringBeforeLast('/'), relation.getAttribute("Target"))
                                if (relation.getAttribute("Type").endsWith("/notesSlide") && zip.getEntry(part) != null)
                                    appendLine("备注：" + text(xml(zip, part).documentElement))
                                if (relation.getAttribute("Type").endsWith("/chart") && zip.getEntry(part) != null)
                                    appendLine("图表数据：" + text(xml(zip, part).documentElement))
                                if (part in images) appendLine("图片：$part")
                            }
                        }
                    }
                    DocumentSection("slide:${index + 1}", "第 ${index + 1} 张幻灯片", slideText)
                }
                DocumentIndex(sections, images, "包含文字、表格、备注及图表缓存；完整页面排版需转换为 PDF。", documentTables)
            }
            else -> {
                val strings = if (zip.getEntry("xl/sharedStrings.xml") != null) {
                    val values = xml(zip, "xl/sharedStrings.xml").getElementsByTagNameNS("*", "si")
                    (0 until values.length).map { text(values.item(it) as Element) }
                } else emptyList()
                val worksheets = entries.filter { it.name.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml")) }.sortedBy { it.name }
                DocumentIndex(worksheets.mapIndexed { index, entry ->
                    val cells = xml(zip, entry.name).getElementsByTagNameNS("*", "c")
                    val content = (0 until cells.length).joinToString("\n") { i ->
                        val cell = cells.item(i) as Element
                        val value = cell.getElementsByTagNameNS("*", "v").item(0)?.textContent.orEmpty()
                        val displayed = if (cell.getAttribute("t") == "s") strings.getOrNull(value.toIntOrNull() ?: -1).orEmpty()
                            else if (cell.getAttribute("t") == "inlineStr") text(cell) else value
                        val formula = cell.getElementsByTagNameNS("*", "f").item(0)?.textContent
                        "${cell.getAttribute("r")}: $displayed" + (formula?.let { "（公式：$it；值为文件中的缓存）" } ?: "")
                    }
                    DocumentSection("sheet:${index + 1}", entry.name, content)
                }, images, "读取单元格和公式缓存，不执行工作簿公式。")
            }
        }
    }

    private fun children(element: Element) = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
    private fun text(element: Element): String = buildString {
        fun visit(node: Element) {
            if (node.localName == "oMath") { append("\\(").append(math(node)).append("\\)"); return }
            when (node.localName) {
                "t", "v" -> append(node.textContent)
                "tab" -> append('\t')
                "br" -> append('\n')
                else -> {
                    children(node).forEach(::visit)
                    if (node.localName in setOf("p", "tr", "oMath")) append('\n')
                }
            }
        }
        visit(element)
    }.trim()
    private fun table(element: Element): String = children(element).filter { it.localName == "tr" }.joinToString("\n") { row ->
        children(row).filter { it.localName == "tc" }.joinToString(" | ") { cell ->
            val span = (cell.getElementsByTagNameNS("*", "gridSpan").item(0) as? Element)
                ?.getAttributeNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "val")
            val vertical = cell.getElementsByTagNameNS("*", "vMerge").item(0) as? Element
            val merge = vertical?.getAttributeNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "val")
            text(cell).replace("\n", " ") + (span?.let { " [跨 $it 列]" } ?: "") +
                (vertical?.let { if (merge == "restart") " [纵向合并起点]" else " [续接上方单元格]" } ?: "")
        }
    }
    private fun math(node: Element): String {
        fun part(name: String) = children(node).firstOrNull { it.localName == name }?.let(::math).orEmpty()
        return when (node.localName) {
            "t" -> node.textContent
            "f" -> "\\frac{" + part("num") + "}{" + part("den") + "}"
            "sSup" -> "{" + part("e") + "}^{" + part("sup") + "}"
            "sSub" -> "{" + part("e") + "}_{" + part("sub") + "}"
            "sSubSup" -> "{" + part("e") + "}_{" + part("sub") + "}^{" + part("sup") + "}"
            "rad" -> "\\sqrt" + part("deg").takeIf { it.isNotBlank() }.orEmpty().let { if (it.isEmpty()) "" else "[$it]" } + "{" + part("e") + "}"
            "limLow" -> "{" + part("e") + "}_{" + part("lim") + "}"
            "limUpp" -> "{" + part("e") + "}^{" + part("lim") + "}"
            "oMath", "e", "r", "num", "den", "sup", "sub", "deg", "lim" -> children(node).joinToString("") { math(it) }
            else -> if (node.localName.endsWith("Pr")) "" else
                "[未完整还原的公式结构：" + node.localName + " " + children(node).joinToString("") { math(it) } + "]"
        }
    }
    private fun relationships(zip: ZipFile, path: String): Map<String, String> {
        val parent = path.substringBeforeLast('/')
        val relPath = parent + "/_rels/" + path.substringAfterLast('/') + ".rels"
        if (zip.getEntry(relPath) == null) return emptyMap()
        val rels = xml(zip, relPath).getElementsByTagNameNS("*", "Relationship")
        return (0 until rels.length).mapNotNull { i ->
            val relation = rels.item(i) as Element
            if (relation.getAttribute("TargetMode") == "External") null
            else relation.getAttribute("Id") to resolvePart(parent, relation.getAttribute("Target"))
        }.toMap()
    }
    private fun resolvePart(parent: String, target: String): String {
        val parts = mutableListOf<String>()
        (if (target.startsWith('/')) target.drop(1) else "$parent/$target").split('/').forEach {
            when (it) { ".." -> { require(parts.isNotEmpty()); parts.removeAt(parts.lastIndex) }; ".", "" -> Unit; else -> parts += it }
        }
        return parts.joinToString("/")
    }
    private fun bytes(zip: ZipFile, path: String): ByteArray {
        val entry = zip.getEntry(path) ?: error("文档缺少 $path")
        require(entry.size in 0..MAX_XML_BYTES.toLong()) { "文档部件过大：$path" }
        return zip.getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_XML_BYTES) { "文档部件过大" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    private fun xml(zip: ZipFile, path: String) = parseOfficeXml(bytes(zip, path))

    internal fun parseOfficeXml(data: ByteArray, factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance()): org.w3c.dom.Document {
        // Android's DOM parser rejects the desktop SAX feature names. Reject DTDs
        // before parsing and block entity resolution independently of those features.
        val charset = when {
            data.size >= 2 && (data[0] == 0xfe.toByte() && data[1] == 0xff.toByte() || data[0] == 0.toByte() && data[1] == '<'.code.toByte()) -> Charsets.UTF_16BE
            data.size >= 2 && (data[0] == 0xff.toByte() && data[1] == 0xfe.toByte() || data[0] == '<'.code.toByte() && data[1] == 0.toByte()) -> Charsets.UTF_16LE
            else -> Charsets.UTF_8
        }
        val content = data.toString(charset).removePrefix("\uFEFF")
        require(!content.contains("<!DOCTYPE", ignoreCase = true)) { "文档 XML 不支持 DTD 或外部实体" }
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        for ((feature, value) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        )) {
            try { factory.setFeature(feature, value) }
            catch (_: ParserConfigurationException) { /* Guard and resolver also protect Android's parser. */ }
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("文档不允许读取外部实体") }
        }.parse(InputSource(StringReader(content)))
    }
}
