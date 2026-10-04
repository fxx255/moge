package com.moge.app.ui.capture

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/** Small, dependency-free text extraction for files commonly shared into the app. */
internal object DocumentTextExtractor {
    private const val MAX_CHARS = 120_000

    fun extract(file: File): String {
        require(file.isFile && file.length() > 0) { "文件不存在或为空" }
        return when (file.extension.lowercase()) {
            "docx" -> zipXml(file, "word/document.xml")
            "pptx" -> zipXmlEntries(file, "ppt/slides/slide")
            "xlsx" -> zipXml(file, "xl/sharedStrings.xml")
            "txt", "md", "csv", "json", "xml", "html", "htm" ->
                file.readText(StandardCharsets.UTF_8).trim()
            "pdf" -> pdfText(file)
            "doc", "ppt", "xls" -> legacyOfficeText(file)
            else -> "文件：${file.name}\n暂不支持直接提取此格式的文字，请将内容复制到对话框后再提问。"
        }.trim().take(MAX_CHARS)
            .ifBlank { "文件：${file.name}\n未提取到可读文字，请检查文件内容或将页面截图后提问。" }
    }

    private fun zipXml(file: File, entryName: String): String =
        zipXmlEntries(file, entryName).trim()

    private fun zipXmlEntries(file: File, prefix: String): String {
        val parts = mutableListOf<String>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && (entry.name == prefix || entry.name.startsWith(prefix) && entry.name.endsWith(".xml"))) {
                    val raw = String(zip.readBytes(), StandardCharsets.UTF_8)
                    val text = TAG.replace(raw, " ")
                        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&quot;", "\"").replace("&apos;", "'")
                        .replace(WHITESPACE, " ").trim()
                    if (text.isNotBlank()) parts += text
                }
                zip.closeEntry()
            }
        }
        return parts.joinToString("\n\n")
    }

    /** Uncompressed PDF text operators are readable; compressed PDFs get a clear fallback. */
    private fun pdfText(file: File): String {
        val raw = String(file.readBytes(), StandardCharsets.ISO_8859_1)
        val chunks = Regex("BT(.*?)ET", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(raw)
            .map { match ->
                Regex("\\((?:\\\\.|[^)])*\\)").findAll(match.groupValues[1]).joinToString(" ") { text ->
                    text.value.removePrefix("(").removeSuffix(")").replace("\\n", " ").replace("\\\\", "\\")
                }
                    .replace(Regex("/[A-Za-z]+|-?\\d+(?:\\.\\d+)?"), " ")
                    .replace(WHITESPACE, " ").trim()
            }.filter { it.isNotBlank() }.toList()
        return chunks.joinToString("\n").ifBlank {
            "文件：${file.name}\nPDF 文本需要在本机解析；当前文件未提供可直接读取的文字层，请将页面截图后提问。"
        }
    }

    private fun legacyOfficeText(file: File): String {
        val raw = file.readBytes()
        val ascii = String(raw, StandardCharsets.ISO_8859_1)
            .replace(Regex("[^\\u0020-\\u007E\\r\\n\\t]"), " ")
            .replace(WHITESPACE, " ").trim()
        return ascii.ifBlank {
            "文件：${file.name}\n旧版 Office 二进制格式未包含可直接读取的文字层，请另存为 DOCX/PPTX 后再上传。"
        }
    }

    private val TAG = Regex("<[^>]+>")
    private val WHITESPACE = Regex("\\s+")
}
