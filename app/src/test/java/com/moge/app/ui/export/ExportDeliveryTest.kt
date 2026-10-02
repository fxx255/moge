package com.moge.app.ui.export

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.moge.app.ui.viewer.fileProviderAuthority
import com.moge.app.ui.viewer.galleryNeedsLegacyPermission
import com.moge.app.ui.viewer.imageShareIntent
import com.moge.app.ui.viewer.saveImagesToGallery
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ExportDeliveryTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var media: TestMediaProvider

    @Before fun prepare() {
        Dispatchers.setMain(StandardTestDispatcher())
        media = TestMediaProvider(context)
        ShadowContentResolver.registerProviderInternal("media", media)
    }
    @After fun reset() { Dispatchers.resetMain() }

    private fun png(name: String): File = File(context.cacheDir, "${ExportLimits.CACHE_DIRECTORY}/test/$name.png").apply {
        parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        try { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @Test fun `all pages are saved and become visible only after copies complete`() = runTest {
        val paths = listOf(png("one"), png("two"))
        val progress = mutableListOf<Int>()
        saveImagesToGallery(context, paths.map { it.absolutePath }) { saved, _ ->
            progress += saved
            assertTrue(media.rows.values.all { it.pending == 1 })
        }
        assertEquals(listOf(1, 2), progress)
        assertEquals(2, media.rows.size)
        assertTrue(media.rows.values.all { it.pending == 0 && it.file.length() > 0 })
        assertEquals(2, media.rows.values.map { it.name }.distinct().size)
    }

    @Test fun `failure of later page rolls back earlier pages and pending rows`() = runTest {
        val first = png("one")
        try {
            saveImagesToGallery(context, listOf(first.absolutePath, File(context.cacheDir, "missing.png").absolutePath))
            fail("Expected save to fail")
        } catch (_: IllegalStateException) { }
        assertTrue(media.rows.isEmpty())
        assertEquals(1, media.deletions)
    }

    @Test fun `cancelling multi page gallery write rolls back its entire sequence`() = runTest {
        val first = png("one"); val second = png("two")
        lateinit var job: Job
        job = launch(start = CoroutineStart.LAZY) {
            saveImagesToGallery(context, listOf(first.absolutePath, second.absolutePath)) { _, _ -> job.cancel() }
        }
        job.start(); job.join()
        assertTrue(job.isCancelled)
        assertTrue(media.rows.isEmpty())
        assertEquals(1, media.deletions)
    }

    @Test fun `multi image intent grants all ordered image URIs without text or metadata`() {
        // Use FileProvider's actual narrow path strategy. Manifest resources are independently
        // packaged by Android; this test deliberately needs no app/Activity or DI startup.
        val authority = fileProviderAuthority(context)
        val cacheField = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(null) as MutableMap<String, Any>
        val type = Class.forName("androidx.core.content.FileProvider\$SimplePathStrategy")
        val strategy = type.getDeclaredConstructor(String::class.java).apply { isAccessible = true }.newInstance(authority)
        type.getDeclaredMethod("addRoot", String::class.java, File::class.java).apply { isAccessible = true }
            .invoke(strategy, ExportLimits.CACHE_DIRECTORY, File(context.cacheDir, ExportLimits.CACHE_DIRECTORY))
        // FileProvider hardcodes Android '/' canonical-path separators. On a Windows JVM its
        // real strategy rejects even valid paths, so adapt ONLY URI lookup for that host.
        val providerStrategy = if (File.separatorChar != '\\') strategy else {
            val contract = Class.forName("androidx.core.content.FileProvider\$PathStrategy")
            val root = File(context.cacheDir, ExportLimits.CACHE_DIRECTORY).canonicalFile.toPath()
            Proxy.newProxyInstance(contract.classLoader, arrayOf(contract)) { _, method, args ->
                check(method.name == "getUriForFile")
                val path = (args!![0] as File).canonicalFile.toPath()
                require(path.startsWith(root))
                Uri.Builder().scheme("content").authority(authority).appendPath(ExportLimits.CACHE_DIRECTORY)
                    .apply { root.relativize(path).forEach { appendPath(it.toString()) } }.build()
            }
        }
        val previous = cache.put(authority, providerStrategy)
        try {
            val files = listOf(png("one"), png("two"))
            val intent = imageShareIntent(context, files.map { it.absolutePath })
            assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
            assertEquals("image/png", intent.type)
            @Suppress("DEPRECATION")
            val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals(2, uris.size)
            assertEquals(uris, (0 until intent.clipData!!.itemCount).map { intent.clipData!!.getItemAt(it).uri })
            assertTrue(uris.all { it.scheme == "content" && it.authority == authority && it.path!!.startsWith("/answer_exports/") })
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
            val single = imageShareIntent(context, listOf(files.first().absolutePath))
            assertEquals(Intent.ACTION_SEND, single.action)
            try {
                val outside = File(context.cacheDir, "outside.png").apply { writeText("image") }
                imageShareIntent(context, listOf(outside.absolutePath))
                fail("Other cache directories must stay private")
            } catch (_: IllegalArgumentException) { }
        } finally { if (previous == null) cache.remove(authority) else cache[authority] = previous }
    }

    @Test fun `modern gallery write needs no storage permission`() { assertFalse(galleryNeedsLegacyPermission()) }

    @Test @Config(sdk = [28])
    fun `Android 26 to 28 uses the legacy permission branch`() { assertTrue(galleryNeedsLegacyPermission()) }

    private class TestMediaProvider(private val appContext: Context) : ContentProvider() {
        data class Row(val file: File, val name: String, var pending: Int)
        val rows = linkedMapOf<Uri, Row>()
        var deletions = 0
        private var id = 0
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val result = Uri.withAppendedPath(uri, (++id).toString())
            val file = File(appContext.cacheDir, "gallery_$id")
            rows[result] = Row(file, values!!.getAsString(MediaStore.Images.Media.DISPLAY_NAME),
                values.getAsInteger(MediaStore.Images.Media.IS_PENDING))
            Shadows.shadowOf(appContext.contentResolver).registerOutputStream(result, file.outputStream())
            return result
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(rows.getValue(uri).file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            rows.getValue(uri).pending = values!!.getAsInteger(MediaStore.Images.Media.IS_PENDING)
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            rows.remove(uri)?.file?.delete(); deletions++; return 1
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri) = "image/png"
    }
}
