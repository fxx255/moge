package com.moge.app.ui.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moge.app.ui.markdown.LocalFigurePathResolver
import com.moge.app.ui.photo.PhotoEdits
import com.moge.app.ui.photo.decodeUprightPhoto
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val VIEWER_MAX_SCALE = 5f
private const val VIEWER_DOUBLE_TAP_SCALE = 2.5f
/** 未放大时向下拖动超过这个距离就关闭查看器。 */
private const val VIEWER_DRAG_DISMISS_PX = 140f
private const val VIEWER_DECODE_MAX_PX = 4096

private sealed interface ViewerImage {
    data object Loading : ViewerImage
    data object Missing : ViewerImage
    class Ready(val bitmap: ImageBitmap) : ViewerImage
}

/**
 * 可缩放的单页：双指捏合缩放、双击放大/还原、放大后单指拖动平移，
 * 边界与缩放下限都做夹取，越界自动回弹（缩小到 1 时位移归零）。
 * 未放大时：轻点关闭、向下拖动关闭；横向拖动让给 HorizontalPager 翻页。
 */
@Composable
internal fun ZoomableImage(
    storedPath: String,
    pageLabel: String,
    onTapToClose: () -> Unit,
    onSwipeDownToClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // 未放大时的下拉位移（用于「下拉关闭」），放在 pointerInput 外面才不会随重组丢失
    var dragDown by remember { mutableFloatStateOf(0f) }
    val photoRevision by PhotoEdits.revision.collectAsStateWithLifecycle()
    val resolver = LocalFigurePathResolver.current
    val dark = MogeTheme.paper.isChalk
    val image by produceState<ViewerImage>(ViewerImage.Loading, storedPath, dark, photoRevision) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                resolver.resolve(storedPath, dark)
                    ?.let { decodeUprightPhoto(it, VIEWER_DECODE_MAX_PX) }
                    ?.let { ViewerImage.Ready(it.asImageBitmap()) }
            }.getOrNull() ?: ViewerImage.Missing
        }
    }

    // 换页或图片被旋转后复位，避免上一张的缩放/位移带过来
    LaunchedEffect(storedPath, photoRevision) {
        scale.snapTo(1f)
        offsetX.snapTo(0f)
        offsetY.snapTo(0f)
        dragDown = 0f
    }

    fun maxOffset(currentScale: Float, dimension: Int): Float =
        (dimension * (currentScale - 1f) / 2f).coerceAtLeast(0f)

    fun clampX(value: Float, atScale: Float): Float {
        if (!value.isFinite() || !atScale.isFinite()) return 0f
        val bound = maxOffset(atScale, boxSize.width)
        return value.coerceIn(-bound, bound)
    }

    fun clampY(value: Float, atScale: Float): Float {
        if (!value.isFinite() || !atScale.isFinite()) return 0f
        val bound = maxOffset(atScale, boxSize.height)
        return value.coerceIn(-bound, bound)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            // 不能直接用 detectTransformGestures：它过了 touch slop 就消费所有位移，
            // HorizontalPager 再也收不到横滑。这里按手势意图决定消费谁：
            // - 双指（捏合）：消费 → 缩放
            // - 单指且已放大：消费 → 平移
            // - 单指未放大、以横向为主：不消费 → 交给 Pager 翻页
            // - 单指未放大、以纵向为主：消费 → 下拉关闭
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var lastCentroid: Offset? = null
                    var totalPanX = 0f
                    var totalPanY = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        val centroid = pressed.fold(Offset.Zero) { acc, c -> acc + c.position } /
                            pressed.size.toFloat()
                        val zoom = event.calculateZoom()
                        if (!centroid.x.isFinite() || !centroid.y.isFinite() || !zoom.isFinite() || zoom <= 0f) {
                            lastCentroid = null
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val pan = lastCentroid?.let { centroid - it } ?: Offset.Zero
                        lastCentroid = centroid
                        if (!pan.x.isFinite() || !pan.y.isFinite()) {
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val zooming = abs(zoom - 1f) > 0.001f
                        val multiTouch = pressed.size >= 2

                        totalPanX += pan.x
                        totalPanY += pan.y

                        if (!multiTouch && scale.value <= 1.0005f && !zooming &&
                            abs(totalPanX) > abs(totalPanY)
                        ) {
                            continue
                        }

                        val next = (scale.value * zoom).coerceIn(1f, VIEWER_MAX_SCALE)
                        if (scale.value <= 1.0005f && !multiTouch && !zooming) {
                            dragDown = (dragDown + pan.y).coerceAtLeast(0f)
                            if (dragDown > VIEWER_DRAG_DISMISS_PX) {
                                dragDown = 0f
                                onSwipeDownToClose()
                            } else {
                                // 受限挂起作用域里不能直接调 Animatable 的挂起成员，丢到 scope 里
                                val target = dragDown
                                scope.launch { offsetY.snapTo(target) }
                            }
                        } else {
                            dragDown = 0f
                            val panX = pan.x
                            val panY = pan.y
                            scope.launch {
                                scale.snapTo(next)
                                offsetX.snapTo(clampX(offsetX.value + panX, next))
                                offsetY.snapTo(clampY(offsetY.value + panY, next))
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                    // 松手：下拉没到阈值就回弹
                    if (dragDown > 0f) {
                        dragDown = 0f
                        scope.launch { offsetY.animateTo(0f) }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (scale.value <= 1.0005f) onTapToClose() },
                    onDoubleTap = { tap ->
                        if (!tap.x.isFinite() || !tap.y.isFinite()) return@detectTapGestures
                        val current = scale.value
                        val target = if (current > 1.0005f) 1f else VIEWER_DOUBLE_TAP_SCALE
                        val center = Offset(boxSize.width / 2f, boxSize.height / 2f)
                        // 让双击点保持不动：offset2 = offset1 + (T - center - offset1) * (1 - s2/s1)
                        val rel = tap - center - Offset(offsetX.value, offsetY.value)
                        val ratio = target / current
                        val nextX = if (target == 1f) 0f else clampX(offsetX.value + rel.x * (1f - ratio), target)
                        val nextY = if (target == 1f) 0f else clampY(offsetY.value + rel.y * (1f - ratio), target)
                        scope.launch {
                            launch { scale.animateTo(target) }
                            launch { offsetX.animateTo(nextX) }
                            offsetY.animateTo(nextY)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val current = image) {
            ViewerImage.Loading -> CircularProgressIndicator(color = Color.White)
            ViewerImage.Missing -> Text("图片文件不可用", color = Color.White)
            is ViewerImage.Ready -> Image(
                bitmap = current.bitmap,
                contentDescription = pageLabel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .graphicsLayer(
                        scaleX = scale.value,
                        scaleY = scale.value,
                        translationX = offsetX.value,
                        translationY = offsetY.value,
                    ),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
