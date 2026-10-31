package com.moge.app.ui.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.moge.app.data.image.PhotoCropEdit
import com.moge.app.runtime.SharedImages
import com.moge.app.ui.photo.PhotoCropDialog

/** Keep the imported batch outside the input until every photo has been confirmed. */
@Composable
internal fun SharedImageCropSequence(
    share: SharedImages,
    onComplete: () -> Unit,
    onDismiss: () -> Unit,
    onCropStarted: (String) -> Unit,
    onCropClosed: (String) -> Unit,
    saveCrop: suspend (String, PhotoCropEdit) -> Unit,
) {
    var index by rememberSaveable(share.id) { mutableIntStateOf(0) }
    val path = share.photoPaths.getOrNull(index) ?: return
    key(path) {
        PhotoCropDialog(
            path = path,
            onCropped = {
                if (index < share.photoPaths.lastIndex) index++ else onComplete()
            },
            onDismiss = onDismiss,
            onCropStarted = { onCropStarted(path) },
            onCropClosed = { onCropClosed(path) },
            saveCrop = { saveCrop(path, it) },
        )
    }
}
