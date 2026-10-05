package com.moge.app.data.figure

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.moge.app.domain.diagram.DiagramNode
import com.moge.app.domain.diagram.DiagramNodeShape
import com.moge.app.domain.diagram.DiagramSpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FigureImageStoreVersionTest {
    private val root = ApplicationProvider.getApplicationContext<Application>().cacheDir
    private val spec = DiagramSpec("cached", listOf(DiagramNode("block", "LPF", DiagramNodeShape.BLOCK)), emptyList())
    private inner class Store(version: String, private val fail: Boolean = false) : FigureImageStore<DiagramSpec>(
        { root }, "version-test", "diagram", DiagramSpec.serializer(), version,
    ) {
        var draws = 0
        override fun describe(spec: DiagramSpec) = spec.title
        override fun draw(spec: DiagramSpec, dark: Boolean): Bitmap {
            check(!fail) { "render failed" }
            draws++
            return Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        }
    }

    @Test fun `old message paths refresh both themes once and retain their version records`() {
        val old = Store("old")
        val path = old.render(spec, false)!!
        val darkPath = old.resolve(path, true)!!
        val updated = Store("updated")
        assertEquals(path, updated.resolve(path, false))
        assertEquals(darkPath, updated.resolve(path, true))
        assertEquals(2, updated.draws)
        repeat(3) { updated.resolve(path, false); updated.resolve(path, true) }
        assertEquals(2, updated.draws)
        updated.dir().listFiles()!!.forEach { it.setLastModified(1L) }
        updated.retainOnly(setOf(File(path).name), 2L)
        assertTrue(updated.dir().listFiles()!!.count { it.extension == "version" } == 2)
        updated.resolve(path, false)
        assertEquals(2, updated.draws)
    }

    @Test fun `failed upgrade keeps original diagram readable`() {
        val path = Store("old").render(spec, false)!!
        val original = File(path).readBytes()
        assertEquals(path, Store("updated", fail = true).resolve(path, false))
        assertArrayEquals(original, File(path).readBytes())
    }
}
