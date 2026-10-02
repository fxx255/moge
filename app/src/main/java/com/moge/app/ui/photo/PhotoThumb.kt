package com.moge.app.ui.photo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme

/**
 * 题目照片缩略图：按 EXIF 转正、裁满方框，点开查看大图。
 * 和解答里的 [com.moge.app.ui.markdown.InlineFigure] 分开：那是整行宽、标注「图 n」的生成图表。
 * 裁剪 / 旋转改写原文件后，[PhotoEdits.revision] 变化会让缩略图重新解码。
 */
@Composable
fun PhotoThumb(path: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val thumbnail = rememberThumbnail(path, THUMB_MAX_PX)
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .clip(shape)
            .background(MogeTheme.paper.scratch)
            .border(1.dp, MogeTheme.paper.cardStroke.copy(alpha = 0.3f), shape)
            .clickable(onClickLabel = "查看$label") { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        when (thumbnail) {
            is Thumbnail.Ready -> {
                val painted = remember(thumbnail.bitmap) { thumbnail.bitmap.asImageBitmap() }
                Image(painted, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Thumbnail.Loading -> Text("…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Thumbnail.Failed -> Text("照片读不出来", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal const val THUMB_MAX_PX = 480
