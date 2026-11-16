package com.moge.app.ui.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.moge.app.ui.viewer.fileProviderAuthority
import java.io.File

internal fun pdfShareIntent(context: Context, file: File): Intent {
    check(file.isFile && file.length() > 0) { "PDF 文件不存在" }
    val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
    return Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, file.nameWithoutExtension)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
