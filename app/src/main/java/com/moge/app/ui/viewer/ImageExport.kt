package com.moge.app.ui.viewer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** 与 AndroidManifest 里 FileProvider 的 authorities 保持一致。 */
internal fun fileProviderAuthority(context: Context): String = "${context.packageName}.files"

/** Android 10 起 MediaStore 写自己的图片不需要任何存储权限；更早的系统要 WRITE_EXTERNAL_STORAGE。 */
internal fun galleryNeedsLegacyPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

/** Gallery IO is shared by the viewer and multi-page answer export. */
internal suspend fun saveImagesToGallery(
    context: Context,
    paths: List<String>,
    onProgress: suspend (saved: Int, total: Int) -> Unit = { _, _ -> },
): String = com.moge.app.data.image.writeGalleryImages(context, paths, onProgress)

/** 通过 FileProvider 把应用私有图片交给系统分享面板。 */
internal fun shareImage(context: Context, path: String) {
    shareImages(context, listOf(path))
}

/** Grants only these image URIs. Sequential PNG pages use ACTION_SEND_MULTIPLE. */
internal fun imageShareIntent(context: Context, paths: List<String>): Intent {
    require(paths.isNotEmpty()) { "没有可分享的图片" }
    val uris = ArrayList<Uri>(paths.map { path ->
        val file = File(path)
        check(file.isFile && file.length() > 0) { "图片文件不存在" }
        FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
    })
    return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = paths.map(::imageMimeType).distinct().singleOrNull() ?: "image/*"
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
        else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        clipData = ClipData.newRawUri("墨格解答", uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal fun shareImages(context: Context, paths: List<String>) {
    context.startActivity(
        Intent.createChooser(imageShareIntent(context, paths), "分享图片").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

internal fun imageMimeType(path: String): String = com.moge.app.data.image.galleryImageMimeType(path)
