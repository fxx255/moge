package com.moge.app.ui.photo

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moge.app.data.image.PhotoCropEdit
import com.moge.app.data.image.decodeEditedPhoto
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Exercise the rotate button, real crop gestures and saved source pixels on MuMu 12-2. */
@RunWith(AndroidJUnit4::class)
class PhotoCropRotationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fourRotationsKeepExactlyTheOriginallySelectedPixels() {
        val file = File.createTempFile("crop-rotation-", ".png", compose.activity.cacheDir)
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(bitmap.width * bitmap.height) { index ->
                val x = index % bitmap.width
                val y = index / bitmap.width
                Color.rgb(x % 256, y % 256, (x + y) % 256)
            }
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        val edits = CopyOnWriteArrayList<PhotoCropEdit>()
        var accepted = 0
        try {
            compose.setContent { MogeTheme {
                PhotoCropDialog(file.path, onCropped = { accepted++ }, onDismiss = {}, saveCrop = { edits.add(it) })
            } }
            val area = compose.onNodeWithTag("photo-crop-area")
            val outer = area.fetchSemanticsNode().boundsInRoot
            val inner = compose.onNodeWithTag("photo-crop-viewport").fetchSemanticsNode().boundsInRoot
            val crop = initialCropFraming(IntSize(1200, 800), IntSize(inner.width.toInt(), inner.height.toInt())).cropRect
            area.performTouchInput {
                down(crop.bottomRight + inner.topLeft - outer.topLeft)
                moveBy(Offset(-crop.width * .4f, -crop.height * .35f), delayMillis = 96)
                up()
            }
            fun confirm() {
                val count = accepted
                compose.onNodeWithText("确定").performClick()
                compose.waitUntil(5_000) { accepted > count }
            }
            confirm()
            assertFalse("A partial selection is required to detect resets", edits.last().isIdentity)
            val selected = decodeEditedPhoto(file.path, edits.last())
            try {
                repeat(4) { turn ->
                    compose.onNodeWithText("逆时针 90°").performClick()
                    confirm()
                    val expected = rotatePhotoBitmap(selected, -(turn + 1))
                    val actual = decodeEditedPhoto(file.path, edits.last())
                    try {
                        assertEquals(expected.width, actual.width)
                        assertEquals(expected.height, actual.height)
                        assertTrue("Rotation ${turn + 1} changed the selected source pixels", expected.sameAs(actual))
                    } finally {
                        actual.recycle()
                        if (expected !== selected) expected.recycle()
                    }
                }
            } finally { selected.recycle() }
        } finally { file.delete() }
    }
}
