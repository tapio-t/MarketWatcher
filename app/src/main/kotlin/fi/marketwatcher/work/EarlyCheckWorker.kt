package fi.marketwatcher.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fi.marketwatcher.data.Settings
import fi.marketwatcher.data.SignalRepository
import fi.marketwatcher.engine.Explain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * The early check (see [EarlySchedule]): runs every hour, but downloads only inside the window after
 * the US close (23:30–03:30 Helsinki for VOO's close with an Alpha Vantage key, 03:30–10:30 for FRED's,
 * on weekday nights) and only until a background check has seen that day's FRED S&P 500 close. Downloads only the S&P 500, so the sharp-fall and 200-day-average
 * notices do not wait ~13 hours for VIX. Notifications found at night are held until 08:00 by
 * [NotificationGate].
 *
 * Until 0.22 each run queued the next one as one-time work behind itself (APPEND_OR_REPLACE). When
 * Android stopped and restarted a run, the next run was queued twice, and the waits added up: on
 * 11.11.2026 the check for 09.11 was still waiting until 13.11. A periodic job can't pile up.
 */
class EarlyCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val settings = Settings(ctx)
        if (!settings.hasApiKey) return@withContext Result.success()
        val window = EarlySchedule.windowAt(ZonedDateTime.now()) ?: return@withContext Result.success()
        val expected = window.expected
        val repo = SignalRepository(ctx)
        val checked = repo.checkedPriceDate()
        if (checked != null && checked >= expected) return@withContext Result.success()
        // Before FRED's close is due, only a new VOO close can change anything (0.24).
        if (window.vooOnly && (!settings.hasAvKey || repo.hasVooClose(expected))) return@withContext Result.success()
        try {
            val o = CheckRunner.run(ctx, priceOnly = true)
            val ok = o.priceDate != null && o.priceDate >= expected
            settings.recordCheck(Instant.now(), when {
                ok -> o.text
                window.vooOnly -> "Evening check (VOO estimate for ${Explain.dmy(expected)}): " + o.text
                else -> "Early check: FRED has no S&P 500 close for ${Explain.dmy(expected)} yet. " + o.text
            })
        } catch (e: Exception) {
            settings.recordCheck(Instant.now(), "Early check failed: ${e.message ?: e.javaClass.simpleName}")
        }
        // The next hourly run tries again; never retry here (that is what piled up before 0.23).
        Result.success()
    }

    companion object {
        private const val WORK_NAME = "early_sp500_check_hourly"
        /** The one-time chain used up to 0.22; cancelled on every app start. */
        private const val OLD_WORK_NAME = "early_sp500_check"

        /** Called on app start: KEEP leaves a running schedule alone. */
        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork(OLD_WORK_NAME)
            val req = PeriodicWorkRequestBuilder<EarlyCheckWorker>(EarlySchedule.INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
