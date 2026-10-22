package com.moge.app.runtime

import android.util.Log
import com.moge.app.data.image.PhotoCropEdit
import com.moge.app.data.image.applyPhotoCrop
import com.moge.app.data.image.PreparedPhotoCrop
import com.moge.app.data.image.QuestionPhotoFiles
import com.moge.app.data.image.writePhotoFileAtomically
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.domain.ConversationMode
import com.moge.app.imaging.PhotoWorkPriority
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/** Confirmation waits only for original crop saving. Optional presentation handoff follows it. */
internal class QuestionPhotoCropPreparation(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val queue: QuestionPhotoPreparation,
    private val priority: PhotoWorkPriority,
) {
    private data class Snapshot(val path: String, val fingerprint: String)
    private class Session {
        var saving = false // Guarded by this session's monitor.
        lateinit var snapshot: Deferred<Snapshot?>
    }
    private val sessions = ConcurrentHashMap<String, Session>()
    private val handoffs = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    fun begin(path: String, mode: ConversationMode) {
        if (mode != ConversationMode.SOLVE) return
        sessions.computeIfAbsent(path) {
            Session().also { session -> session.snapshot = scope.async { snapshot(path, session) } }
        }
    }

    private suspend fun snapshot(path: String, session: Session): Snapshot? = try {
        if (!settings.current().questionPhotoPreprocessing) null else {
            val original = File(path)
            // Only opening the stream shares a lock with confirmation. Copying never does.
            // An already-open stream retains the old inode after atomic crop replacement.
            val input = synchronized(session) { if (session.saving) null else original.inputStream() }
            if (input == null) null else {
                val snapshot = File(original.parentFile, ".crop-${UUID.randomUUID()}")
                val operation = coroutineContext
                val digest = MessageDigest.getInstance("SHA-256")
                input.use {
                    writePhotoFileAtomically(snapshot.path) { temporary ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                priority.backgroundCheckpoint { operation.ensureActive() }
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                            }
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                queue.enqueueSnapshot(snapshot.path, hash)
                Snapshot(snapshot.path, hash)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(QuestionPhotoPreparation.TAG, "Cannot prewarm crop; accepted photo will be queued", error)
        null
    }

    fun end(path: String) { sessions.remove(path) }

    /** Prevent ordinary draft/send enqueue from racing the richer accepted-crop registration. */
    suspend fun awaitHandoff(path: String) {
        while (true) (handoffs[path] ?: return).await()
    }

    suspend fun save(path: String, mode: ConversationMode, edit: PhotoCropEdit) {
        // Application ownership survives cancellation of a screen waiting for confirmation.
        val session = sessions.remove(path)
        if (session != null) synchronized(session) { session.saving = true }
        val handoff = CompletableDeferred<Unit>()
        handoffs.put(path, handoff)
        scope.async {
            try {
                priority.beginSave().use { applyPhotoCrop(path, edit) }
            } catch (failure: Throwable) {
                finishHandoff(path, handoff)
                throw failure
            }
            // No copy/hash/settings/WorkManager wait remains on the caller's critical path.
            scope.launch {
                try {
                    if (mode != ConversationMode.SOLVE) return@launch
                    val hash = if (handoffs[path] === handoff) QuestionPhotoFiles.fingerprint(path) else null
                    val snapshot = session?.snapshot?.await()
                    if (hash == null || handoffs[path] !== handoff) {
                        if (snapshot != null) {
                            File(snapshot.path).delete()
                            QuestionPhotoFiles.discard(snapshot.path)
                        }
                        return@launch
                    }
                    val reusable = snapshot?.let { PreparedPhotoCrop(it.path, it.fingerprint, edit) }
                    queue.enqueueAccepted(path, hash, mode, reusable)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(QuestionPhotoPreparation.TAG, "Cannot hand off accepted crop; original remains available", error)
                } finally { finishHandoff(path, handoff) }
            }
        }.await()
    }

    private fun finishHandoff(path: String, handoff: CompletableDeferred<Unit>) {
        handoffs.remove(path, handoff)
        handoff.complete(Unit)
    }
}
