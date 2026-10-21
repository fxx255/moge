package com.moge.app.runtime

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.moge.app.core.ApplicationScope
import com.moge.app.data.image.QuestionPhotoFiles
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

/** Registration is application-owned; WorkManager continues across navigation and process restart. */
@Singleton
class QuestionPhotoPreparation @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
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
                    val request = OneTimeWorkRequestBuilder<QuestionPhotoPreparationWorker>()
                        .setInputData(workDataOf(SOURCE to source, FINGERPRINT to hash)).build()
                    val pathKey = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        "question-photo-$pathKey-$hash", ExistingWorkPolicy.KEEP, request,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Cannot queue photo cleanup; original remains available", error)
            }
        }
    }

    internal companion object {
        const val SOURCE = "source"
        const val FINGERPRINT = "fingerprint"
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
        dependencies.photoPreprocessor().prepare(source, ConversationMode.SOLVE, hash)
        return Result.success()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface QuestionPhotoPreparationDependencies {
    fun photoPreprocessor(): QuestionPhotoPreprocessor
}
