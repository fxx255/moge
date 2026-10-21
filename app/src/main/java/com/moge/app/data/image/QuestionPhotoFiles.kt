package com.moge.app.data.image

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Original photos remain model inputs. Content-addressed companions are presentation only. */
object QuestionPhotoFiles {
    private const val SUFFIX = ".clean-"
    private val companionName = Regex("^(.+)\\.clean-[0-9a-f]{64}\\.png$")
    private val changes = MutableStateFlow<Map<String, Long>>(emptyMap())
    fun revision(path: String): Long = changes.value[revisionKey(path)] ?: 0L
    fun observeRevision(path: String) = revisionKey(path).let { key ->
        changes.map { it[key] ?: 0L }.distinctUntilChanged()
    }

    // Android can return these two aliases for the same primary-user private file.
    // UI revision lookups must stay free of filesystem IO; only user 0 has this alias.
    private fun revisionKey(path: String): String = if (path.startsWith("/data/user/0/")) {
        "/data/data/" + path.removePrefix("/data/user/0/")
    } else path

    /** IO only. A digest also detects recrops whose file size and timestamp happen to match. */
    internal fun fingerprint(path: String): String? {
        val file = File(path)
        if (!file.isFile || file.length() == 0L) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun output(path: String, fingerprint: String): File {
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
        return File(path + SUFFIX + fingerprint + ".png")
    }

    /** Called by image loaders off the main thread; a missing/stale companion falls back to the source. */
    fun displayPath(path: String): String = runCatching {
        if (companions(path).isEmpty()) return@runCatching path
        val hash = fingerprint(path) ?: return@runCatching path
        output(path, hash).takeIf { it.isFile && it.length() > 0 }?.path ?: path
    }.getOrDefault(path)

    internal fun published(path: String) {
        // Only this photo reloads: a gallery batch must not repeatedly decode every visible image.
        val key = revisionKey(path)
        changes.update { it + (key to ((it[key] ?: 0L) + 1L)) }
    }

    internal fun companions(path: String): List<File> {
        val source = File(path)
        return source.parentFile?.listFiles { _, name ->
            name.startsWith(source.name + SUFFIX) && companionName.matches(name)
        }?.toList().orEmpty()
    }

    internal fun discard(path: String) { companions(path).forEach { it.delete() } }

    /** The janitor retains companions for as long as the original is referenced. */
    internal fun originalOf(file: File): File? = companionName.matchEntire(file.name)
        ?.groupValues?.get(1)?.let { File(file.parentFile, it) }
}
