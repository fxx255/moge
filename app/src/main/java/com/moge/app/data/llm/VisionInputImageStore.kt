package com.moge.app.data.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Reuses the same vision bytes for current and historical images; never stores credentials. */
@Singleton
class VisionInputImageStore @Inject constructor(@ApplicationContext context: Context) {
    private val directory = File(context.cacheDir, "model-vision-inputs")

    @Synchronized
    fun encode(path: String): String? {
        val source = File(path)
        if (!source.isFile) return null
        val hash = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().buffered().use { stream ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(bytes)
                    if (count < 0) break
                    digest.update(bytes, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return null
        val cached = File(directory, "jpeg-2048-q95-v1-$hash.base64")
        runCatching { cached.takeIf { it.isFile }?.readText(Charsets.US_ASCII) }.getOrNull()
            ?.takeIf { it.startsWith("/9j/") && it.length > 8 }?.let {
                cached.setLastModified(System.currentTimeMillis())
                return it
            }
        val encoded = ImagePrep.encodeForVision(path) ?: return null
        // Cache write/eviction failure must not prevent sending an otherwise readable image.
        runCatching {
            check(directory.isDirectory || directory.mkdirs())
            val temporary = File.createTempFile("vision-", ".tmp", directory)
            try {
                temporary.writeText(encoded, Charsets.US_ASCII)
                check(temporary.renameTo(cached))
            } finally {
                temporary.delete()
            }
            var size = directory.listFiles().orEmpty().sumOf { it.length() }
            directory.listFiles().orEmpty().sortedBy { it.lastModified() }.forEach { file ->
                if (size > MAX_CACHE_BYTES && file != cached) {
                    val bytes = file.length()
                    if (file.delete()) size -= bytes
                }
            }
        }
        return encoded
    }

    private companion object {
        const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    }
}
