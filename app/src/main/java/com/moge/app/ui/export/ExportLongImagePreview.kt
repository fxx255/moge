package com.moge.app.ui.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ExportPreviewTile(val top: Int, val bottom: Int)

internal fun exportPreviewTiles(height: Int): List<ExportPreviewTile> =
    (0 until height step 1024).map { top -> ExportPreviewTile(top, minOf(top + 1024, height)) }

/** Decode only visible strips, sized by width instead of the entire long image's height. */
@Suppress("DEPRECATION")
internal fun decodeExportPreviewTile(path: String, width: Int, tile: ExportPreviewTile, targetWidth: Int): Bitmap? {
    val decoder = BitmapRegionDecoder.newInstance(path, false) ?: return null
    return try {
        var sample = 1
        while (width / (sample * 2) >= targetWidth.coerceAtLeast(1)) sample *= 2
        decoder.decodeRegion(Rect(0, tile.top, width, tile.bottom), BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        })
    } finally { decoder.recycle() }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun ExportLongImagePreview(path: String, description: String, modifier: Modifier = Modifier) {
    var infoLoaded by remember(path) { mutableStateOf(false) }
    val info by produceState<ExportImageInfo?>(null, path) {
        value = withContext(Dispatchers.IO) { exportImageInfo(path) }
        infoLoaded = true
    }
    val dimensions = info
    if (dimensions == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(if (infoLoaded) "图片无法读取，请重新生成。" else "正在读取图片…")
        }
        return
    }
    key(path) {
        var zoom by remember { mutableFloatStateOf(1f) }
        val density = LocalDensity.current
        val tiles = remember(dimensions.height) { exportPreviewTiles(dimensions.height) }
        BoxWithConstraints(modifier.testTag("export-preview").semantics {
            contentDescription = description
            customActions = listOf(CustomAccessibilityAction(if (zoom == 1f) "放大预览" else "恢复预览") {
                zoom = if (zoom == 1f) 2f else 1f; true
            })
        }) {
            val displayWidth = maxWidth * zoom
            val targetWidth = with(density) { displayWidth.roundToPx() }.coerceAtLeast(1)
            val horizontal = androidx.compose.foundation.rememberScrollState()
            Box(Modifier.fillMaxWidth().horizontalScroll(horizontal)) {
                LazyColumn(Modifier.width(displayWidth).fillMaxHeight().pointerInput(path) {
                    detectTapGestures(onDoubleTap = { zoom = if (zoom == 1f) 2f else 1f })
                }) {
                    items(tiles, key = { it.top }) { tile ->
                        val height = displayWidth * ((tile.bottom - tile.top).toFloat() / dimensions.width)
                        var loaded by remember(path, tile, targetWidth) { mutableStateOf(false) }
                        val image by produceState<Bitmap?>(null, path, tile, targetWidth) {
                            value = try {
                                withContext(Dispatchers.IO) { decodeExportPreviewTile(path, dimensions.width, tile, targetWidth) }
                            } catch (error: CancellationException) { throw error }
                            catch (_: Exception) { null }
                            loaded = true
                        }
                        Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
                            image?.let { bitmap ->
                                Image(bitmap.asImageBitmap(), contentDescription = null,
                                    modifier = Modifier.fillMaxWidth().height(height),
                                    contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.High)
                            }
                            if (loaded && image == null) Text("此处图片读取失败，请重新生成。",
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
