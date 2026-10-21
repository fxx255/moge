package com.moge.app.runtime

import android.util.Log
import com.moge.app.data.image.PhotoCropEdit
import com.moge.app.data.image.applyPhotoCrop
import com.moge.app.data.image.PreparedPhotoCrop
import com.moge.app.data.image.QuestionPhotoFiles
import com.moge.app.data.image.writePhotoFileAtomically
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.domain.ConversationMode
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** A crop session waits only for the source copy, never for native color processing. */
internal class QuestionPhotoCropPreparation(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val queue: QuestionPhotoPreparation,
) {
    private data class Snapshot(val path: String, val fingerprint: String)
    private val sessions = ConcurrentHashMap<String, Deferred<Snapshot?>>()

    fun begin(path: String, mode: ConversationMode) {
        if (mode != ConversationMode.SOLVE) return
        sessions.computeIfAbsent(path) {
            scope.async {
                try {
                    if (!settings.current().questionPhotoPreprocessing) return@async null
                    val original = File(path)
                    val snapshot = File(original.parentFile, ".crop-${UUID.randomUUID()}")
                    // The original is atomically replaced by edits. Copying its open
                    // stream retains one complete version, including EXIF orientation.
                    writePhotoFileAtomically(snapshot.path) { original.copyTo(it, overwrite = true) }
                    val hash = QuestionPhotoFiles.fingerprint(snapshot.path) ?: return@async null
                    queue.enqueueSnapshot(snapshot.path, hash)
                    Snapshot(snapshot.path, hash)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(QuestionPhotoPreparation.TAG, "Cannot prewarm crop; accepted photo will be queued", error)
                    null
                }
            }
        }
    }

    fun end(path: String) { sessions.remove(path) }

    suspend fun save(path: String, mode: ConversationMode, edit: PhotoCropEdit) {
        // Application ownership keeps the original save + durable registration
        // together if navigation or configuration changes cancel the UI waiter.
        val source = sessions[path]
        scope.async {
            val snapshot = source?.await()
            val before = if (snapshot != null) fingerprintOrNull(path) else null
            applyPhotoCrop(path, edit)
            if (source != null) sessions.remove(path, source)
            if (mode != ConversationMode.SOLVE) return@async
            val after = fingerprintOrNull(path) ?: return@async
            val reusable = snapshot?.takeIf { it.fingerprint == before }
                ?.let { PreparedPhotoCrop(it.path, it.fingerprint, edit) }
            queue.enqueueAccepted(path, after, mode, reusable)
        }.await()
    }

    private fun fingerprintOrNull(path: String): String? = try {
        QuestionPhotoFiles.fingerprint(path)
    } catch (error: Exception) {
        // Optional cleanup metadata must not turn a successfully saved crop into a UI failure.
        Log.w(QuestionPhotoPreparation.TAG, "Cannot inspect crop version; original remains available", error)
        null
    }
}
