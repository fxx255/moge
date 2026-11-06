package com.moge.app.data.image

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GallerySaveRepositoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var media: GalleryTestProvider
    private fun source(name: String = "one.png", content: String = "same image bytes") = File(context.cacheDir, name).apply { writeText(content) }

    @Before fun setup() {
        context.getSharedPreferences("gallery_save_receipts_v1", 0).edit().clear().commit()
        media = GalleryTestProvider(context)
        ShadowContentResolver.registerProviderInternal("media", media)
    }

    @Test fun `repeat clicks and reopening the viewer save one gallery copy`() {
        val image = source()
        assertFalse(GallerySaveRepository(context).save(image.path).alreadySaved)
        assertArrayEquals(image.readBytes(), media.rows.values.single().readBytes())
        context.contentResolver.openAssetFileDescriptor(media.rows.keys.single(), "r")!!.use { assertTrue(it.length > 0) }
        repeat(5) { assertTrue(GallerySaveRepository(context).save(image.path).alreadySaved) }
        assertEquals(1, media.rows.size)
        assertEquals(1, media.inserts.get())
    }

    @Test fun `same contents at another path are recognized while edited contents can be saved`() {
        val image = source()
        val saver = GallerySaveRepository(context)
        saver.save(image.path)
        assertTrue(saver.save(source("copy.png").path).alreadySaved)
        image.writeText("modified pixels")
        assertFalse(saver.save(image.path).alreadySaved)
        assertEquals(2, media.rows.size)
    }

    @Test fun `deleting the gallery copy allows saving it again`() {
        val saver = GallerySaveRepository(context)
        val image = source()
        saver.save(image.path)
        media.delete(media.rows.keys.single(), null, null)
        assertFalse(saver.save(image.path).alreadySaved)
        assertEquals(1, media.rows.size)
        assertEquals(2, media.inserts.get())
    }

    @Test fun `copy or publish failures remove partial rows and do not prevent retry`() {
        val saver = GallerySaveRepository(context)
        val image = source()
        for (copyFailure in listOf(true, false)) {
            media.failWrite = copyFailure; media.failPublish = !copyFailure
            assertTrue(runCatching { saver.save(image.path) }.isFailure)
            assertTrue(media.rows.isEmpty())
        }
        media.failWrite = false; media.failPublish = false
        assertFalse(saver.save(image.path).alreadySaved)
        assertTrue(saver.save(image.path).alreadySaved)
        assertEquals(1, media.rows.size)
    }

    @Test fun `concurrent viewers atomically check and save the same content once`() {
        val image = source()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = (1..8).map { pool.submit(Callable { start.await(); GallerySaveRepository(context).save(image.path) }) }
            start.countDown()
            assertEquals(1, results.map { it.get(5, TimeUnit.SECONDS) }.count { !it.alreadySaved })
            assertEquals(1, media.inserts.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun `a missing original creates neither a receipt nor a gallery row`() {
        assertTrue(runCatching { GallerySaveRepository(context).save(File(context.cacheDir, "missing.png").path) }.isFailure)
        assertTrue(media.rows.isEmpty())
        assertEquals(0, media.inserts.get())
    }

    @Test @Config(sdk = [28]) fun `legacy saves are deduplicated and a removed file may be saved again`() {
        val saver = GallerySaveRepository(context)
        val image = source()
        assertFalse(saver.save(image.path).alreadySaved)
        assertTrue(GallerySaveRepository(context).save(image.path).alreadySaved)
        val receipt = context.getSharedPreferences("gallery_save_receipts_v1", 0).all.values.single() as String
        assertTrue(File(receipt).delete())
        assertFalse(saver.save(image.path).alreadySaved)
        context.getSharedPreferences("gallery_save_receipts_v1", 0).all.values.forEach { File(it as String).delete() }
    }
}

/** Exercise real ContentResolver copy/publish/read behavior; no mocked save result. */
internal class GalleryTestProvider(private val app: Context) : ContentProvider() {
    init { attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true; grantUriPermissions = true }) }
    val rows = ConcurrentHashMap<Uri, File>()
    val inserts = AtomicInteger()
    @Volatile var failWrite = false
    @Volatile var failPublish = false
    @Volatile var writeGate: CountDownLatch? = null
    @Volatile var writing = false
    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val id = inserts.incrementAndGet()
        val result = Uri.withAppendedPath(uri, id.toString())
        rows[result] = File(app.cacheDir, "gallery_guard_$id").apply { delete() }
        return result
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = rows[uri] ?: throw FileNotFoundException(uri.toString())
        if (mode == "r") return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        if (failWrite) throw FileNotFoundException("injected write failure")
        writing = true
        check(writeGate?.await(5, TimeUnit.SECONDS) != false)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY)
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = if (failPublish) 0 else 1
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        rows.remove(uri)?.delete(); return 1
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri) = "image/png"
}
