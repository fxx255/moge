package com.moge.app.ui.photo

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The photo clips to its viewport; the selection draws above it with room for complete handles. */
@Composable
internal fun PhotoCropViewport(
    preview: Bitmap,
    editor: CropViewportState,
    saving: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val handleSpace = 12.dp
    val inset = with(LocalDensity.current) { handleSpace.toPx() }.let { Offset(it, it) }
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f)
    val image = androidx.compose.runtime.remember(preview) { preview.asImageBitmap() }
    Box(modifier.testTag("photo-crop-area").cropGestures(editor, saving, motionEnabled, inset)) {
        Canvas(Modifier.matchParentSize().padding(handleSpace).clip(RoundedCornerShape(6.dp))
            .background(Color.Black).onSizeChanged(editor::resize).testTag("photo-crop-viewport")) {
            if (editor.viewport == IntSize.Zero) return@Canvas
            val photo = displayedImageRect(IntSize(preview.width, preview.height), editor.viewport,
                editor.zoom, editor.imageOffset)
            // Use the same viewport transform as source-pixel mapping, without resizing an Image child.
            drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(preview.width, preview.height),
                dstOffset = IntOffset(photo.left.roundToInt(), photo.top.roundToInt()),
                dstSize = IntSize(photo.width.roundToInt().coerceAtLeast(1), photo.height.roundToInt().coerceAtLeast(1)))
            val crop = editor.cropRect
            if (crop != Rect.Zero) {
                val shade = Color.Black.copy(alpha = 0.58f)
                drawRect(shade, Offset.Zero, Size(size.width, crop.top.coerceAtLeast(0f)))
                drawRect(shade, Offset(0f, crop.bottom), Size(size.width, (size.height - crop.bottom).coerceAtLeast(0f)))
                drawRect(shade, Offset(0f, crop.top), Size(crop.left.coerceAtLeast(0f), crop.height))
                drawRect(shade, Offset(crop.right, crop.top), Size((size.width - crop.right).coerceAtLeast(0f), crop.height))
            }
        }
        // Keep this canvas outside the photo clip. A full-image selection must retain all four corners.
        Canvas(Modifier.matchParentSize()) {
            val crop = editor.cropRect
            if (crop == Rect.Zero) return@Canvas
            translate(inset.x, inset.y) { drawCropSelection(crop, accent, outline) }
        }
    }
}

private fun DrawScope.drawCropSelection(crop: Rect, accent: Color, outline: Color) {
    fun gridLine(start: Offset, end: Offset) {
        drawLine(outline.copy(alpha = 0.5f), start, end, 1.5.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.7f), start, end, 0.75.dp.toPx())
    }
    for (fraction in listOf(1f / 3f, 2f / 3f)) {
        val x = crop.left + crop.width * fraction
        val y = crop.top + crop.height * fraction
        if (crop.width >= 48.dp.toPx()) gridLine(Offset(x, crop.top), Offset(x, crop.bottom))
        if (crop.height >= 48.dp.toPx()) gridLine(Offset(crop.left, y), Offset(crop.right, y))
    }
    // Light and dark backing preserve contrast over both white documents and dark photos.
    // One stroke draws all four sides at the same width, in either orientation.
    for ((color, width) in listOf(outline to 4.dp, Color.White to 2.5.dp, accent to 1.25.dp)) {
        drawRect(color, crop.topLeft, crop.size, style = Stroke(width.toPx()))
    }
    // Short edges need smaller corners and shorter bars, rather than overlapping
    // fixed-size handles into a thick border. Hit targets remain in cropGestures.
    val cornerRadius = minOf(9.dp.toPx(), minOf(crop.width, crop.height) / 6f)
    fun edgeHandle(center: Offset, horizontal: Boolean, edgeLength: Float) {
        val halfHandle = minOf(12.dp.toPx(), edgeLength / 2f - cornerRadius - 4.5.dp.toPx() - 3.dp.toPx())
        if (halfHandle < 3.dp.toPx()) return
        val half = if (horizontal) Offset(halfHandle, 0f) else Offset(0f, halfHandle)
        for ((color, width) in listOf(outline to 9.dp, Color.White to 7.dp, accent to 5.dp)) {
            drawLine(color, center - half, center + half, width.toPx(), cap = StrokeCap.Round)
        }
    }
    edgeHandle(Offset(crop.center.x, crop.top), horizontal = true, edgeLength = crop.width)
    edgeHandle(Offset(crop.center.x, crop.bottom), horizontal = true, edgeLength = crop.width)
    edgeHandle(Offset(crop.left, crop.center.y), horizontal = false, edgeLength = crop.height)
    edgeHandle(Offset(crop.right, crop.center.y), horizontal = false, edgeLength = crop.height)
    for (corner in listOf(crop.topLeft, crop.topRight, crop.bottomLeft, crop.bottomRight)) {
        drawCircle(outline, cornerRadius, corner)
        drawCircle(Color.White, cornerRadius * 7f / 9f, corner)
        drawCircle(accent, cornerRadius * 5f / 9f, corner)
    }
}
