package com.moge.app.runtime

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** WorkManager persists the schedule across process death/reboot; startup catches up immediately. */
class HistoryRetentionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            val dependencies = EntryPointAccessors.fromApplication(applicationContext, HistoryRetentionDependencies::class.java)
            dependencies.generationManager().recoverOnStartup()
            dependencies.historyRetention().sweep()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(WORK_NAME, "history retention failed; retry later", e)
            Result.retry()
        }
    }

    companion object {
        internal const val WORK_NAME = "history-retention"
        internal const val INTERVAL_HOURS = 12L

        fun schedule(context: Context, enabled: Boolean = true) {
            if (!enabled) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                return
            }
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HistoryRetentionWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                    .setInitialDelay(INTERVAL_HOURS, TimeUnit.HOURS)
                    .addTag(WORK_NAME)
                    .build(),
            )
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface HistoryRetentionDependencies {
    fun historyRetention(): HistoryRetention
    fun generationManager(): GenerationManager
}
