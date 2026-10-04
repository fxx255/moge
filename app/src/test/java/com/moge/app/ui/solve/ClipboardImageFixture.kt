package com.moge.app.ui.solve

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID

internal class ClipboardImageFixture(
    context: Context,
    mime: String? = "image/png",
    denyMetadata: Boolean = false,
    unreadable: Boolean = false,
    corrupt: Boolean = false,
    onOpen: () -> Unit = {},
) {
    val source = File(context.cacheDir, "clipboard-${UUID.randomUUID()}.png").apply {
        if (corrupt) writeText("not an image")
        else outputStream().use { out ->
            val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
            try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) } finally { bitmap.recycle() }
        }
    }
    private val providerAuthority = "clipboard.images.${UUID.randomUUID()}"
    val uri: Uri = Uri.parse("content://$providerAuthority/opaque-image")

    init {
        ShadowContentResolver.registerProviderInternal(providerAuthority, object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri): String? {
                if (denyMetadata) throw SecurityException("Metadata unavailable")
                return mime
            }
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                onOpen()
                if (unreadable) throw SecurityException("Clipboard access expired")
                return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        }.apply {
            attachInfo(context, ProviderInfo().apply {
                authority = providerAuthority
                exported = true
                grantUriPermissions = true
            })
        })
    }
}
