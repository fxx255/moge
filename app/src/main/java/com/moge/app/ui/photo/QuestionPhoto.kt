package com.moge.app.ui.photo

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme

/** Sent question attachments retain the whole selected region; composer thumbnails stay compact. */
@Composable
internal fun QuestionPhoto(path: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val strings = rememberAppStrings()
    val photo = rememberThumbnail(path, maxPx = 4096, preferProcessed = true)
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.fillMaxWidth().clip(shape).background(MogeTheme.paper.scratch)
        .border(1.dp, MogeTheme.paper.cardStroke.copy(alpha = .3f), shape)
        .clickable(onClickLabel = strings.text(R.string.ui_view_1_s_98c1c, label), onClick = onClick), contentAlignment = Alignment.Center) {
        when (photo) {
            is Thumbnail.Ready -> {
                val bitmap = remember(photo.bitmap) { photo.bitmap.asImageBitmap() }
                Image(bitmap, label, contentScale = ContentScale.Fit, filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height))
            }
            else -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(if (photo == Thumbnail.Failed) strings.text(R.string.ui_could_not_read_photo_1c6d4) else "…", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
