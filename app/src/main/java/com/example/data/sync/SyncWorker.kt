package com.example.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.local.AppDatabase
import com.example.data.repository.StoreRepository

/**
 * Background CoroutineWorker for safely synchronizing local Room database records
 * with Firestore whenever the device has network connectivity.
 */
class SyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting WorkManager background data sync (attempt #$runAttemptCount)...")

        return try {
            val db = AppDatabase.getDatabase(applicationContext)
            val repository = StoreRepository(db, applicationContext)

            val summary = repository.syncAllData()

            if (summary.success) {
                Log.i(
                    TAG,
                    "Background sync completed successfully! Total records synced: ${summary.totalSyncedRecords}"
                )

                // Store last successful sync time in SharedPreferences
                val prefs = applicationContext.getSharedPreferences("auto_backup_prefs", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                prefs.edit().putLong("last_workmanager_sync_time", now).apply()
                val syncPrefs = applicationContext.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                syncPrefs.edit().putLong("last_auto_sync_time", now).apply()

                val outputData = workDataOf(
                    KEY_SUCCESS to true,
                    KEY_MESSAGE to summary.message,
                    KEY_TOTAL_SYNCED to summary.totalSyncedRecords,
                    KEY_PRODUCTS_COUNT to summary.productsCount,
                    KEY_SALES_COUNT to summary.salesCount,
                    KEY_PURCHASES_COUNT to summary.purchasesCount,
                    KEY_TIMESTAMP to System.currentTimeMillis()
                )

                Result.success(outputData)
            } else {
                Log.w(TAG, "Background sync failed: ${summary.message}")
                if (runAttemptCount < MAX_RETRY_COUNT) {
                    Result.retry()
                } else {
                    Result.failure(workDataOf(KEY_SUCCESS to false, KEY_MESSAGE to summary.message))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "Background sync job cancelled cleanly.")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled exception during background sync: ${e.message}", e)
            if (runAttemptCount < MAX_RETRY_COUNT) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_SUCCESS to false, KEY_MESSAGE to (e.message ?: "Unknown error")))
            }
        }
    }

    companion object {
        const val TAG = "SyncWorker"
        const val WORK_NAME_PERIODIC = "kali_mata_store_periodic_sync"
        const val WORK_NAME_ONE_TIME = "kali_mata_store_instant_sync"
        const val SYNC_WORK_TAG = "store_background_sync_tag"

        const val KEY_SUCCESS = "sync_success"
        const val KEY_MESSAGE = "sync_message"
        const val KEY_TOTAL_SYNCED = "sync_total_records"
        const val KEY_PRODUCTS_COUNT = "sync_products_count"
        const val KEY_SALES_COUNT = "sync_sales_count"
        const val KEY_PURCHASES_COUNT = "sync_purchases_count"
        const val KEY_TIMESTAMP = "sync_timestamp"

        private const val MAX_RETRY_COUNT = 3
    }
}
