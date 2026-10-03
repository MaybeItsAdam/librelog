package uk.co.maybeitssoftware.librelog.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import uk.co.maybeitssoftware.librelog.LibrelogApplication

/**
 * Background sync while the app isn't open (SPEC.md §12.5): every ~15
 * minutes with a network, push anything written offline and pull what other
 * devices wrote. Realtime covers the foreground.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LibrelogApplication
        val sync = app.model.sync
        sync.start()
        return if (sync.problem == SyncError.Offline) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "librelog-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
