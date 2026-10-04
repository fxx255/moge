package com.moge.app.ui.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.moge.app.domain.SolveMode
import com.moge.app.ui.photo.importPhoto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 取景 → 裁剪 → 确认面板 → 解题页之间传递的一批照片。
 * 经导航参数交给解题页（见 [com.moge.app.ui.Routes.solveCaptured]），进程被回收后 SavedStateHandle 仍能还原。
 */
data class CaptureBatch(
    val photoPaths: List<String>,
    val solveMode: SolveMode,
    val note: String = "",
    /** Imported office/PDF files are converted to text before submission. */
    val documentPaths: List<String> = emptyList(),
)

/**
 * 拍题照片的文件管理。照片一律落在 `filesDir/photos/`（不是 cacheDir）：裁剪是原地改写，
 * 确认面板停留期间进程被回收也不会丢图；提交时 [com.moge.app.runtime.DraftStore.persistAttachments]
 * 看到它已在私有目录内，会原样复用而不再复制。
 */
@Singleton
class CaptureStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private fun photosDir(): File = File(context.filesDir, PHOTOS_DIR).apply { mkdirs() }
    private fun documentsDir(): File = File(context.filesDir, DOCUMENTS_DIR).apply { mkdirs() }

    /** 相机拍照的目标文件（还没写入内容）。 */
    fun newCameraFile(): File = File(photosDir(), "cam_${UUID.randomUUID()}.jpg")

    /** 相册选图：复制进私有目录，返回绝对路径。任何一张失败都向上抛。 */
    suspend fun importFromGallery(uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        uris.map { importPhoto(context, it, photosDir(), "pick").absolutePath }
    }

    /** Copy a shared or picked document into private storage before its provider URI expires. */
    suspend fun importDocument(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty().lowercase()
        val displayName = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("")
        val nameExtension = displayName.substringAfterLast('.', "").lowercase()
        val extension = when {
            mime == "application/pdf" -> "pdf"
            mime == "text/plain" || mime == "text/markdown" -> "txt"
            mime.contains("wordprocessingml") -> "docx"
            mime.contains("presentationml") -> "pptx"
            mime.contains("spreadsheetml") -> "xlsx"
            mime == "application/msword" -> "doc"
            mime == "application/vnd.ms-powerpoint" -> "ppt"
            mime == "application/vnd.ms-excel" -> "xls"
            nameExtension in setOf("pdf", "txt", "md", "doc", "docx", "ppt", "pptx", "xls", "xlsx") -> nameExtension
            else -> uri.toString().substringAfterLast('.', "bin").takeIf { it.length in 1..8 } ?: "bin"
        }
        val target = File(documentsDir(), "doc_${UUID.randomUUID()}.$extension")
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: error("无法读取文件")
        check(target.length() > 0) { "文件为空" }
        target.absolutePath
    }

    suspend fun extractDocument(path: String): String = withContext(Dispatchers.IO) {
        DocumentTextExtractor.extract(File(path))
    }

    /** Render scanned PDF pages so vision models can analyze documents without a text layer. */
    suspend fun renderPdf(path: String, maxPages: Int = MAX_RENDERED_DOCUMENT_PAGES): List<String> =
        withContext(Dispatchers.IO) {
            val source = File(path)
            if (!source.isFile || !source.extension.equals("pdf", ignoreCase = true)) return@withContext emptyList()
            val pages = mutableListOf<String>()
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    for (index in 0 until minOf(renderer.pageCount, maxPages)) {
                        renderer.openPage(index).use { page ->
                            val width = 1400
                            val height = (width * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val target = File(photosDir(), "pdf_${UUID.randomUUID()}.jpg")
                            FileOutputStream(target).use { output ->
                                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "PDF 页面保存失败" }
                            }
                            bitmap.recycle()
                            pages += target.absolutePath
                        }
                    }
                }
            }
            pages
        }

    /** 用户删掉的照片：只删本目录里、确实没被提交过的文件。 */
    suspend fun discard(paths: Collection<String>) = withContext(Dispatchers.IO) {
        val root = photosDir().canonicalPath + File.separator
        paths.map(::File).filter { it.canonicalPath.startsWith(root) }.forEach { it.delete() }
    }

    companion object {
        const val PHOTOS_DIR = "photos"
        const val DOCUMENTS_DIR = "documents"
        const val MAX_RENDERED_DOCUMENT_PAGES = 9

        /** 一道题最多 9 张照片（与解题请求的附件上限一致）。 */
        const val MAX_PHOTOS = 9
    }
}
