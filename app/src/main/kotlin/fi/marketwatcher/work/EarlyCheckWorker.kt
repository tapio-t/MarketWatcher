package fi.marketwatcher.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import fi.marketwatcher.data.Settings
import fi.marketwatcher.engine.Explain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * The early check (see [EarlySchedule]): 20:30 New York time on US weekdays (03:30 Helsinki),
 * downloads only the S&P 500 so the sharp-fall and 200-day-average notices do not wait ~13 hours
 * for VIX. Retries hourly until the expected close is there or the deadline passes, then schedules
 * the next weekday. Notifications found at night are held until 08:00 by [NotificationGate].
 */
class EarlyCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val settings = Settings(ctx)
        val now = ZonedDateTime.now()
        val expected = inputData.getString(KEY_EXPECTED)
        val deadline = inputData.getLong(KEY_DEADLINE, 0L)
        if (!settings.hasApiKey || expected == null) { schedule(ctx, ExistingWorkPolicy.APPEND_OR_REPLACE); return@withContext Result.success() }

        val found = try {
            val o = CheckRunner.run(ctx, priceOnly = true)
            val ok = o.priceDate != null && o.priceDate >= expected
            settings.recordCheck(Instant.now(), if (ok) o.text
                else "Early check: FRED has no S&P 500 close for ${Explain.dmy(expected)} yet. " + o.text)
            ok
        } catch (e: Exception) {
            settings.recordCheck(Instant.now(), "Early check failed: ${e.message ?: e.javaClass.simpleName}")
            false
        }
        val run = EarlySchedule.Run(now, expected, Instant.ofEpochMilli(deadline).atZone(EarlySchedule.NEW_YORK))
        val retryAt = if (found) null else EarlySchedule.retry(run, now)
        if (retryAt != null) enqueue(ctx, retryAt, expected, run.deadline, ExistingWorkPolicy.APPEND_OR_REPLACE)
        else schedule(ctx, ExistingWorkPolicy.APPEND_OR_REPLACE)
        Result.success()
    }

    companion object {
        private const val WORK_NAME = "early_sp500_check"
        private const val KEY_EXPECTED = "expected"
        private const val KEY_DEADLINE = "deadline"

        /** Schedules the next regular run. On app start [policy] KEEP leaves a pending run alone. */
        fun schedule(ctx: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val run = EarlySchedule.next(ZonedDateTime.now())
            enqueue(ctx, run.at, run.expectedDate, run.deadline, policy)
        }

        private fun enqueue(ctx: Context, at: ZonedDateTime, expected: String, deadline: ZonedDateTime, policy: ExistingWorkPolicy) {
            val delay = Duration.between(ZonedDateTime.now(), at).toMillis().coerceAtLeast(0)
            val req = OneTimeWorkRequestBuilder<EarlyCheckWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_EXPECTED to expected, KEY_DEADLINE to deadline.toInstant().toEpochMilli()))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NAME, policy, req)
        }
    }
}
