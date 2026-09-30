package com.example.data.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Controller to schedule, trigger, cancel, and observe background synchronization
 * tasks managed by Android WorkManager.
 */
object SyncWorkManager {

    private const val TAG = "SyncWorkManager"

    /**
     * Builds network constraints ensuring sync only runs when connected to the internet.
     */
    private fun getSyncConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .build()
    }

    /**
     * Schedules a periodic background sync worker.
     * Android WorkManager enforces a minimum 15-minute interval for periodic requests.
     */
    fun schedulePeriodicSync(
        context: Context,
        intervalMinutes: Long = 15,
        forceUpdate: Boolean = false
    ) {
        val validInterval = intervalMinutes.coerceAtLeast(15L)

        val periodicWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            validInterval,
            TimeUnit.MINUTES,
            // 5 minutes flex interval
            5,
            TimeUnit.MINUTES
        )
            .setConstraints(getSyncConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(SyncWorker.SYNC_WORK_TAG)
            .build()

        val existingWorkPolicy = if (forceUpdate) {
            ExistingPeriodicWorkPolicy.UPDATE
        } else {
            ExistingPeriodicWorkPolicy.KEEP
        }

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME_PERIODIC,
            existingWorkPolicy,
            periodicWorkRequest
        )

        Log.i(TAG, "Scheduled periodic background sync every $validInterval minutes (Policy: $existingWorkPolicy)")
    }

    /**
     * Triggers an immediate one-time background sync when online.
     */
    fun triggerImmediateSync(context: Context): Operation {
        val oneTimeWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(getSyncConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(SyncWorker.SYNC_WORK_TAG)
            .build()

        Log.i(TAG, "Enqueuing immediate background sync via WorkManager...")

        return WorkManager.getInstance(context).enqueueUniqueWork(
            SyncWorker.WORK_NAME_ONE_TIME,
            ExistingWorkPolicy.KEEP,
            oneTimeWorkRequest
        )
    }

    /**
     * Cancels any scheduled periodic sync work.
     */
    fun cancelPeriodicSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.WORK_NAME_PERIODIC)
        Log.i(TAG, "Cancelled periodic background sync work.")
    }

    /**
     * Returns a Flow observing the status of the periodic sync task.
     */
    fun getPeriodicSyncWorkInfoFlow(context: Context): Flow<WorkInfo?> {
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME_PERIODIC)
            .map { list -> list.firstOrNull() }
    }

    /**
     * Returns a Flow observing the status of the immediate one-time sync task.
     */
    fun getOneTimeSyncWorkInfoFlow(context: Context): Flow<WorkInfo?> {
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME_ONE_TIME)
            .map { list -> list.firstOrNull() }
    }
}
