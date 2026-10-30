package com.moge.app.data.share

import android.content.Intent
import android.net.Uri

/** Provider apps disagree on where they place attachments; preserve order and deduplicate. */
@Suppress("DEPRECATION")
internal fun sharedContentUris(intent: Intent): List<Uri> {
    if (intent.action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return emptyList()
    return buildList {
        fun addStream(value: Any?) {
            when (value) {
                is Uri -> add(value)
                is String -> add(Uri.parse(value))
                is Iterable<*> -> value.forEach(::addStream)
                is Array<*> -> value.forEach(::addStream)
            }
        }
        addStream(intent.extras?.get(Intent.EXTRA_STREAM))
        intent.data?.let(::add)
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
        }
    }.distinct().filter { it.scheme in setOf("content", "file") }
}
