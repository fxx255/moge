package com.moge.app.data.document

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class DocumentAttachment(
    val id: String,
    val path: String,
    val name: String,
    val mime: String,
    val bytes: Long,
    val sha256: String,
)

/** Immutable originals survive drafts, sending, retries and process death. */
@Singleton
class DocumentStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun root() = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }
            .substringAfterLast('/').substringAfterLast('\\').take(240)
        val mime = resolver.getType(uri).orEmpty().lowercase()
        val extension = when (mime) {
            "application/pdf" -> "pdf"
            "application/msword" -> "doc"
            "application/vnd.ms-powerpoint" -> "ppt"
            "application/vnd.ms-excel" -> "xls"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            else -> name.substringAfterLast('.', "").lowercase()
        }
        require(extension in EXTENSIONS) { "暂不支持这个文件格式" }
        val id = UUID.randomUUID().toString()
        val target = File(root(), "$id.$extension")
        val temporary = File(root(), "$id.importing")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            resolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= MAX_BYTES) { "单个文档不能超过 50 MB" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: error("无法读取文件")
            require(size > 0) { "文件为空" }
            check(temporary.renameTo(target)) { "文件保存失败" }
            val attachment = DocumentAttachment(id, target.canonicalPath, name.ifBlank { "文档.$extension" },
                mime.ifBlank { mimeFor(extension) }, size, digest.digest().joinToString("") { "%02x".format(it) })
            val metadata = AtomicFile(File(target.path + METADATA_SUFFIX))
            val stream = metadata.startWrite()
            try {
                stream.write(json.encodeToString(DocumentAttachment.serializer(), attachment).toByteArray())
                stream.fd.sync()
                metadata.finishWrite(stream)
            } catch (error: Throwable) { metadata.failWrite(stream); throw error }
            target.canonicalPath
        } catch (error: Throwable) {
            temporary.delete()
            target.delete()
            File(target.path + METADATA_SUFFIX).delete()
            throw error
        }
    }

    fun attachment(path: String, verifyHash: Boolean = false): DocumentAttachment {
        val file = ownedFile(path)
        require(file.isFile && file.length() > 0) { "原文档已不存在，请重新添加" }
        require(file.length() <= MAX_BYTES) { "文档超过 50 MB" }
        val metadata = File(file.path + METADATA_SUFFIX)
        if (metadata.exists()) {
            val saved = json.decodeFromString(DocumentAttachment.serializer(), AtomicFile(metadata).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            require(saved.path == file.canonicalPath && saved.bytes == file.length()) { "文档保存信息不一致，请重新添加" }
            if (verifyHash) require(saved.sha256 == sha256(file)) { "文档内容已改变，请重新添加" }
            return saved
        }
        // Older imports have no metadata. Keep their originals usable without inventing a filename.
        return DocumentAttachment(file.nameWithoutExtension, file.canonicalPath, file.name, mimeFor(file.extension),
            file.length(), sha256(file))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun ownedFile(path: String): File = File(path).canonicalFile.also {
        require(it.parentFile == root().canonicalFile && it.extension.lowercase() in EXTENSIONS) { "文档不在应用附件目录中" }
    }

    companion object {
        const val DIRECTORY = "documents"
        const val METADATA_SUFFIX = ".meta.json"
        const val MAX_BYTES = 50L * 1024 * 1024
        const val MAX_ATTACHMENTS = 8
        val EXTENSIONS = setOf("pdf", "docx", "pptx", "xlsx", "doc", "ppt", "xls", "txt", "md", "csv")
        fun mimeFor(extension: String) = when (extension.lowercase()) {
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "doc" -> "application/msword"
            "ppt" -> "application/vnd.ms-powerpoint"
            "xls" -> "application/vnd.ms-excel"
            else -> "text/plain"
        }
    }
}
