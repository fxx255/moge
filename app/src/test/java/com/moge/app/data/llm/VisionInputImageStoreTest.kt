package com.moge.app.data.llm

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisionInputImageStoreTest {
    @Test fun `reuses actual JPEG across store instances and never revives a missing original`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("vision-cache", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val first = VisionInputImageStore(context).encode(source.path)!!
            assertTrue(first.startsWith("/9j/"))
            mockkObject(ImagePrep)
            try {
                every { ImagePrep.encodeForVision(any(), any(), any()) } throws AssertionError("An unchanged image must not be re-encoded")
                assertEquals(first, VisionInputImageStore(context).encode(source.path))
                source.delete()
                assertNull(VisionInputImageStore(context).encode(source.path))
            } finally { unmockkObject(ImagePrep) }
        } finally {
            bitmap.recycle()
            source.delete()
        }
    }

    @Test fun `changed source invalidates cache and cache eviction reproduces identical bytes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("vision-change", ".png", context.cacheDir)
        val store = VisionInputImageStore(context)
        val bitmap = Bitmap.createBitmap(21, 31, Bitmap.Config.ARGB_8888)
        fun save(color: Int) {
            bitmap.eraseColor(color)
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        try {
            save(android.graphics.Color.WHITE)
            val first = store.encode(source.path)
            save(android.graphics.Color.BLACK)
            val second = store.encode(source.path)
            assertNotEquals(first, second)
            File(context.cacheDir, "model-vision-inputs").listFiles().orEmpty().forEach { it.delete() }
            assertEquals(second, VisionInputImageStore(context).encode(source.path))
        } finally { bitmap.recycle(); source.delete() }
    }
}
