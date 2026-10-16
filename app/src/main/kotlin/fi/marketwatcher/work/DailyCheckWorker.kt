package fi.marketwatcher.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fi.marketwatcher.data.CapeUpdater
import fi.marketwatcher.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The full check, twice a day. FRED adds the S&P 500 close about 03:00 Helsinki the night after the
 * US close, VIX and VIX3M about 16:30–17:00 Helsinki the next day; a new reading needs all three.
 * The S&P 500 close alone is picked up earlier by [EarlyCheckWorker]. The rules live in
 * [NotificationRules]; [CheckRunner] fetches data, applies them and shows or holds the notices
 * (08:00–18:00, [NotificationGate]); Settings shows the result as "Last background check".
 */
class DailyCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val settings = Settings(ctx)
        if (!settings.hasApiKey) {
            settings.recordCheck(Instant.now(), "Skipped: no FRED API key.")
            return@withContext Result.success()
        }
        try {
            settings.recordCheck(Instant.now(), CheckRunner.run(ctx, priceOnly = false).text)
            // Weekly: look for a newer Shiller CAPE file (never fails the check).
            runCatching { CapeUpdater.checkOnline(ctx) }
            Result.success()
        } catch (e: Exception) {
            settings.recordCheck(Instant.now(), "Failed: ${e.message ?: e.javaClass.simpleName} Will retry.")
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "daily_market_check"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<DailyCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
