package com.moge.app.runtime

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.await
import com.moge.app.core.ApplicationScope
import com.moge.app.data.image.QuestionPhotoFiles
import com.moge.app.data.image.PhotoCropEdit
import com.moge.app.data.image.PreparedPhotoCrop
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.domain.ConversationMode
import com.moge.app.imaging.QuestionPhotoPreprocessor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Registration is application-owned; WorkManager continues across navigation and process restart. */
@Singleton
class QuestionPhotoPreparation @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val crops = QuestionPhotoCropPreparation(settings, scope, this)

    fun beginCrop(path: String, mode: ConversationMode) = crops.begin(path, mode)
    fun endCrop(path: String) = crops.end(path)
    suspend fun saveCrop(path: String, mode: ConversationMode, edit: PhotoCropEdit) = crops.save(path, mode, edit)

    internal suspend fun enqueueSnapshot(path: String, hash: String) = register(path, hash, snapshot = true)

    fun enqueue(paths: List<String>, mode: ConversationMode) {
        if (mode != ConversationMode.SOLVE || paths.isEmpty()) return
        scope.launch {
            try {
                if (!settings.current().questionPhotoPreprocessing) return@launch
                for (path in paths.distinct()) {
                    val source = File(path).canonicalPath
                    val hash = QuestionPhotoFiles.fingerprint(source) ?: continue
                    val output = QuestionPhotoFiles.output(source, hash)
                    if (output.isFile && output.length() > 0) continue
                    register(source, hash)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Cannot queue photo cleanup; original remains available", error)
            }
        }
    }

    /** Register before handing an accepted photo to draft/send, so fallback enqueue cannot win the race. */
    internal suspend fun enqueueAccepted(path: String, hash: String, mode: ConversationMode, crop: PreparedPhotoCrop?) {
        if (mode != ConversationMode.SOLVE) return
        try {
            if (settings.current().questionPhotoPreprocessing) register(File(path).canonicalPath, hash, crop)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Accepting the original remains valid. The draft handoff can queue the regular fallback.
            Log.w(TAG, "Cannot queue accepted crop; original remains available", error)
        }
    }

    private suspend fun register(source: String, hash: String, crop: PreparedPhotoCrop? = null, snapshot: Boolean = false) {
        val data = Data.Builder().putAll(workDataOf(SOURCE to source, FINGERPRINT to hash, SNAPSHOT to snapshot))
        if (crop != null) data.putAll(crop.toWorkData())
        val request = OneTimeWorkRequestBuilder<QuestionPhotoPreparationWorker>().setInputData(data.build()).build()
        val pathKey = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
        WorkManager.getInstance(context).enqueueUniqueWork(
            "question-photo-$pathKey-$hash", ExistingWorkPolicy.KEEP, request,
        ).await()
    }

    internal companion object {
        const val SOURCE = "source"
        const val FINGERPRINT = "fingerprint"
        const val SNAPSHOT = "snapshot"
        const val TAG = "QuestionPhotoPreparation"
    }
}

class QuestionPhotoPreparationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val source = inputData.getString(QuestionPhotoPreparation.SOURCE) ?: return Result.failure()
        val hash = inputData.getString(QuestionPhotoPreparation.FINGERPRINT) ?: return Result.failure()
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, QuestionPhotoPreparationDependencies::class.java)
        // Cancellation is propagated by the preprocessor. Processing failures retain the
        // source and finish this optional display task without retrying expensive work.
        finishQuestionPhotoPreparation(source, hash, inputData.preparedPhotoCrop(), dependencies.photoPreprocessor(),
            publishChange = !inputData.getBoolean(QuestionPhotoPreparation.SNAPSHOT, false))
        return Result.success()
    }
}

/** Uses only persisted work inputs; it does not depend on a still-open screen or session map. */
internal suspend fun finishQuestionPhotoPreparation(
    source: String, hash: String, crop: PreparedPhotoCrop?, processor: QuestionPhotoPreprocessor, publishChange: Boolean = true,
) {
    var completed = false
    try {
        // A newer recrop or a discarded photo makes this version irrelevant.
        val current = withContext(Dispatchers.IO) { runCatching { QuestionPhotoFiles.fingerprint(source) }.getOrNull() }
        if (current == hash) {
            if (crop != null) processor.prepare(crop.source, ConversationMode.SOLVE, crop.fingerprint, publishChange = false)
            processor.prepare(source, ConversationMode.SOLVE, hash, crop, publishChange)
        }
        completed = true
    } finally {
        // Cancellation may be retried after process restart: keep its immutable input.
        if (completed && crop != null) withContext(Dispatchers.IO) {
            File(crop.source).delete()
            QuestionPhotoFiles.discard(crop.source)
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface QuestionPhotoPreparationDependencies {
    fun photoPreprocessor(): QuestionPhotoPreprocessor
}
