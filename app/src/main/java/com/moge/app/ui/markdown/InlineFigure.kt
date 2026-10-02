package com.moge.app.ui.markdown

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moge.app.ui.photo.PhotoEdits
import com.moge.app.ui.photo.decodeUprightPhoto
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.theme.MonoFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把消息里存的图表路径换成**当前主题**下的 PNG。
 *
 * 消息只记一个路径（生成时的主题变体）；切换日夜后由 image store 凭 spec 台账
 * 渲染另一份变体，PNG 被清理后也能原样重画。默认实现原样返回，便于预览和测试。
 * 会做文件 IO 和渲染，只能在后台线程调用。
 */
fun interface FigurePathResolver {
    fun resolve(storedPath: String, dark: Boolean): String?
}

val LocalFigurePathResolver = staticCompositionLocalOf {
    FigurePathResolver { path, _ -> path.takeIf { File(it).let { f -> f.isFile && f.length() > 0 } } }
}

/**
 * 内嵌在解答里的生成图：独占整行、宽度撑满解答纸，下方标注「图 n」，点击打开查看器。
 *
 * - 解析与解码都在 IO 线程：重画一张框图要几十毫秒，放主线程会卡住流式滚动；
 * - 解析不到时短暂重试：流式回答里「PNG 落盘」与「路径进消息」之间可能有极短时间差；
 * - 占位框同样可点，绝不出现「点了没反应」（砺行 v1.0.26 反馈）。
 */
@Composable
internal fun InlineFigure(
    path: String,
    onClick: () -> Unit,
    number: Int? = null,
) {
    val dark = MogeTheme.paper.isChalk
    val resolver = LocalFigurePathResolver.current
    val photoRevision by PhotoEdits.revision.collectAsStateWithLifecycle()
    var decodeAttempt by remember(path, dark, resolver, photoRevision) { mutableIntStateOf(0) }
    val loaded by produceState<LoadedFigure?>(null, path, dark, decodeAttempt, photoRevision, resolver) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                val resolved = resolver.resolve(path, dark) ?: return@runCatching null
                decodeUprightPhoto(resolved, FIGURE_DECODE_MAX_PX)?.let { LoadedFigure(resolved, it) }
            }.getOrNull()
        }
        if (value == null && decodeAttempt < DECODE_RETRY_MAX) {
            delay(DECODE_RETRY_DELAY_MS)
            decodeAttempt++
        }
    }
    val label = number?.let { "图 $it" } ?: "生成的图表"
    Column(
        modifier = Modifier.fillMaxWidth().zIndex(FIGURE_Z_INDEX),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val figure = loaded
        if (figure == null) {
            FigurePlaceholder(
                text = if (decodeAttempt < DECODE_RETRY_MAX) "图表加载中…" else "图表暂时无法显示",
                label = label,
                onClick = onClick,
            )
        } else {
            FigureImage(figure, label, onClick)
        }
        if (number != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private class LoadedFigure(val path: String, val bitmap: Bitmap)

@Composable
private fun FigurePlaceholder(text: String, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MogeTheme.paper.scratch)
            .clickable(onClickLabel = "查看$label") { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FigureImage(figure: LoadedFigure, label: String, onClick: () -> Unit) {
    val image = remember(figure) { figure.bitmap.asImageBitmap() }
    val frame = Modifier
        .clip(MaterialTheme.shapes.small)
        .border(BorderStroke(1.dp, MogeTheme.paper.cardStroke.copy(alpha = 0.25f)), MaterialTheme.shapes.small)
    val scrolls = remember(figure) {
        shouldScrollGeneratedImage(File(figure.path), figure.bitmap.width, figure.bitmap.height)
    }
    if (scrolls) {
        // 宽框图塞进手机宽度会小到看不清：保留一个可读的最小画布，放不下就横向滚动。
        // 先量出有限的视口宽度再挂 horizontalScroll，后者会给子项无限宽。
        BoxWithConstraints(Modifier.fillMaxWidth().then(frame)) {
            val viewport = maxWidth.takeIf { it.value.isFinite() && it.value > 0f } ?: DIAGRAM_MIN_INLINE_WIDTH
            Box(Modifier.width(viewport).horizontalScroll(rememberScrollState())) {
                Image(
                    bitmap = image,
                    contentDescription = label,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .width(maxOf(viewport, DIAGRAM_MIN_INLINE_WIDTH))
                        .clickable(onClickLabel = "放大$label") { onClick() },
                )
            }
        }
    } else {
        Image(
            bitmap = image,
            contentDescription = label,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .then(frame)
                .clickable(onClickLabel = "放大$label") { onClick() },
        )
    }
}

@Composable
internal fun FailedFigureHint(index: Int) {
    Text(
        text = "图 ${index + 1} 未能生成，可以点「重新生成」再试一次。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = "图 ${index + 1} 未能生成" },
    )
}

/**
 * 图片/表格在解答纸内的层级。
 *
 * 文本块（AndroidView）与图片是同一 Column 里的兄弟。命中测试按 zIndex 从高到低进行，
 * 文本块的实际高度只要比格位略高，就会盖住靠后的图片与表格并吃掉触摸
 * （砺行反馈的「越靠后越点不动」）。交互元素抬到 1f 就能先被命中。
 */
internal const val FIGURE_Z_INDEX = 1f

private val DIAGRAM_MIN_INLINE_WIDTH = 520.dp
private const val FIGURE_DECODE_MAX_PX = 3200
private const val DECODE_RETRY_MAX = 3
private const val DECODE_RETRY_DELAY_MS = 600L

/** 框图目录下的、或明显很宽的 PNG 走横向滚动。 */
internal fun shouldScrollGeneratedImage(file: File, width: Int, height: Int): Boolean =
    file.parentFile?.name == "diagrams" ||
        (file.extension.equals("png", ignoreCase = true) &&
            (file.name.startsWith("diagram_") ||
                (width >= 1000 && height > 0 && width.toFloat() / height >= 2.1f)))
