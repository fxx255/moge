package com.moge.app.runtime

import androidx.work.Data
import androidx.work.workDataOf
import com.moge.app.data.image.PhotoCropEdit
import com.moge.app.data.image.PreparedPhotoCrop

private const val CROP_SOURCE = "crop-source"
private const val CROP_FINGERPRINT = "crop-fingerprint"
private const val CROP_TURNS = "crop-turns"
private const val CROP_REGION = "crop-region"

internal fun PreparedPhotoCrop.toWorkData(): Data = workDataOf(
    CROP_SOURCE to source, CROP_FINGERPRINT to fingerprint, CROP_TURNS to edit.quarterTurns,
    CROP_REGION to floatArrayOf(edit.left, edit.top, edit.width, edit.height),
)

internal fun Data.preparedPhotoCrop(): PreparedPhotoCrop? {
    val source = getString(CROP_SOURCE) ?: return null
    val fingerprint = getString(CROP_FINGERPRINT) ?: return null
    val region = getFloatArray(CROP_REGION)?.takeIf { it.size == 4 } ?: return null
    return runCatching {
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
        PreparedPhotoCrop(source, fingerprint,
            PhotoCropEdit(getInt(CROP_TURNS, 0), region[0], region[1], region[2], region[3]))
    }.getOrNull()
}
