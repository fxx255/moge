package com.moge.app.data.document

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.moge.app.runtime.PosixAtomicFileShadow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [PosixAtomicFileShadow::class])
class DocumentStoreImportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun providerUri(file: File, mime: String? = null, name: String? = null, denyMetadata: Boolean = false): Uri {
        val providerAuthority = "opaque.documents.${UUID.randomUUID()}"
        ShadowContentResolver.registerProviderInternal(providerAuthority, object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri): String? {
                if (denyMetadata) throw SecurityException("Metadata access denied")
                return mime
            }
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
                if (denyMetadata) throw SecurityException("Metadata access denied")
                return name?.let { MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(it)) } }
            }
            override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        }.apply {
            attachInfo(this@DocumentStoreImportTest.context, ProviderInfo().apply {
                authority = providerAuthority
                exported = true
                grantUriPermissions = true
            })
        })
        return Uri.parse("content://$providerAuthority/42")
    }

    @Test fun opaquePdfIsCopiedEvenWhenProviderRefusesMetadata() = runBlocking {
        val bytes = "%PDF-1.7\noriginal document bytes\n%%EOF".toByteArray()
        val source = File(context.cacheDir, "opaque-pdf").apply { writeBytes(bytes) }
        val store = DocumentStore(context)
        val path = store.import(providerUri(source, denyMetadata = true), nameHint = "微信分享的原文档.pdf")
        source.delete()
        assertArrayEquals(bytes, File(path).readBytes())
        val document = store.attachment(path, verifyHash = true)
        assertEquals("pdf", File(path).extension)
        assertEquals("application/pdf", document.mime)
        assertEquals("微信分享的原文档.pdf", document.name)
    }

    @Test fun opaqueOfficeArchivesAreIdentifiedWithoutFilenameOrMime() = runBlocking {
        val store = DocumentStore(context)
        for ((extension, entry) in listOf("docx" to "word/document.xml", "pptx" to "ppt/presentation.xml", "xlsx" to "xl/workbook.xml")) {
            val source = File(context.cacheDir, "opaque-$extension")
            ZipOutputStream(source.outputStream()).use { zip ->
                for (name in listOf("[Content_Types].xml", entry)) {
                    zip.putNextEntry(ZipEntry(name)); zip.write("<original/>".toByteArray()); zip.closeEntry()
                }
            }
            val original = source.readBytes()
            val path = store.import(providerUri(source, mime = "application/octet-stream"))
            source.delete()
            assertEquals(extension, File(path).extension)
            assertEquals(DocumentStore.mimeFor(extension), store.attachment(path).mime)
            assertEquals("文档.$extension", store.attachment(path).name)
            assertArrayEquals(original, File(path).readBytes())
        }
    }

    @Test fun intentMimeIsUsedWhenProviderDoesNotReturnFileDetails() = runBlocking {
        val source = File(context.cacheDir, "opaque-text").apply { writeText("文件原文") }
        val store = DocumentStore(context)
        val path = store.import(providerUri(source), mimeHint = "text/plain; charset=utf-8", nameHint = "题目.txt")
        assertEquals("txt", File(path).extension)
        assertEquals("题目.txt", store.attachment(path).name)
        assertEquals("文件原文", File(path).readText())
    }

    @Test fun actualPdfWinsOverGenericProviderTypeAndMisleadingExtension() = runBlocking {
        val source = File(context.cacheDir, "opaque-mislabeled").apply { writeText("%PDF-1.7\n%%EOF") }
        val store = DocumentStore(context)
        val path = store.import(providerUri(source, "text/plain", "download.bin"), mimeHint = "application/octet-stream")
        assertEquals("pdf", File(path).extension)
        assertEquals("application/pdf", store.attachment(path).mime)
    }
}
