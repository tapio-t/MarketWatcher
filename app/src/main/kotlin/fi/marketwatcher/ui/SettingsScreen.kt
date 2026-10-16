package fi.marketwatcher.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import fi.marketwatcher.data.BundledData
import fi.marketwatcher.data.CapeHistory
import fi.marketwatcher.data.CapeUpdater
import fi.marketwatcher.data.Settings
import fi.marketwatcher.data.SignalRepository
import fi.marketwatcher.engine.Reading
import fi.marketwatcher.engine.PriceView
import fi.marketwatcher.engine.price
import fi.marketwatcher.work.NotificationGate
import fi.marketwatcher.work.NotificationRules
import fi.marketwatcher.work.QuietHours
import java.time.ZonedDateTime
import fi.marketwatcher.work.NotificationRules.Kind
import fi.marketwatcher.work.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Result of a notification test, shown under its switch. */
private data class TestResult(val text: String, val openSettings: Boolean = false)

private val CHECK_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

/**
 * [latest] is the newest reading the app has loaded and [price] the newest S&P 500 close (which may be
 * newer than [latest]). Tests are built from them right here, so they need no network or background
 * job. Like real notifications they are shown between 08:00 and 18:00, otherwise held until 08:00.
 */
@Composable
internal fun SettingsScreen(settings: Settings, latest: Reading?, price: PriceView?, onSaved: (message: String) -> Unit) {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var notify by remember { mutableStateOf(settings.notifications) }
    var notifySma by remember { mutableStateOf(settings.notifyBelowSma) }
    var notifySharp by remember { mutableStateOf(settings.notifySharpFall) }
    var held by remember { mutableStateOf(NotificationGate.held(ctx)) }
    val results = remember { mutableStateMapOf<Kind, TestResult>() }
    // Which test (if any) waits for the permission answer; null when a switch asked for it.
    var testAfterPermission by remember { mutableStateOf<Kind?>(null) }

    fun openNotificationSettings() {
        runCatching {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun switchOn(kind: Kind) = when (kind) {
        Kind.STATE -> settings.notifications
        Kind.BELOW_SMA -> settings.notifyBelowSma
        Kind.SHARP_FALL -> settings.notifySharpFall
    }

    /** Builds the test from today's data with the shared rules, posts it, then checks Android shows it. */
    fun runTest(kind: Kind) {
        val r = latest ?: run { results[kind] = TestResult("Nothing to test yet: no data has been loaded."); return }
        val p = price ?: r.price()
        val notices = NotificationRules.test(kind, NotificationRules.now(r, SignalRepository.flagsOf(r), p))
        if (notices.isEmpty()) {
            results[kind] = TestResult(if (kind == Kind.SHARP_FALL) "Nothing to test yet: no previous close is available."
                else "Nothing to test yet: the 200-day average is not available.")
            return
        }
        val now = ZonedDateTime.now()
        if (!QuietHours.isOpen(now)) {
            NotificationGate.hold(ctx, notices, now)
            held = NotificationGate.held(ctx)
            results[kind] = TestResult("Held: notifications are shown 08:00–18:00, so this test will be shown at 08:00.")
            return
        }
        Notifier.blocked(ctx, NotificationRules.channelOf(kind))?.let {
            results[kind] = TestResult(it.message, openSettings = true)
            return
        }
        val posted = notices.filter { Notifier.post(ctx, it) }
        if (posted.isEmpty()) {
            results[kind] = TestResult("Posting failed. Check notification settings.", openSettings = true)
            return
        }
        results[kind] = TestResult("Checking…")
        scope.launch {
            // Posting is asynchronous: look for up to 2 seconds before reporting "not shown".
            var delivery = posted.map { Notifier.Delivery.NOT_LISTED }
            for (attempt in 1..8) {
                delay(250)
                delivery = posted.map { Notifier.delivery(ctx, it.id) }
                if (delivery.none { it == Notifier.Delivery.NOT_LISTED }) break
            }
            val off = if (!switchOn(kind)) " This switch is off, so real notifications of this kind are not sent." else ""
            results[kind] = when {
                delivery.all { it == Notifier.Delivery.SHOWN } -> TestResult(
                    "Shown: ${posted.size} test notification${if (posted.size > 1) "s" else ""} " +
                        "with data from ${dmy(p.date)}.$off")
                delivery.any { it == Notifier.Delivery.SHOWN_DND } -> TestResult(
                    "Posted, but Do Not Disturb is on, so Android may hide it.$off", openSettings = true)
                else -> TestResult("Posted, but Android did not show it. Do Not Disturb, a focus mode or this " +
                    "notification type's settings may be hiding it.$off", openSettings = true)
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val kind = testAfterPermission
        testAfterPermission = null
        if (granted) {
            if (kind != null) runTest(kind)
        } else {
            // After a denial, no rationale means Android will not ask again: go to settings instead.
            val permanently = activity != null && Build.VERSION.SDK_INT >= 33 &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
            if (kind != null) results[kind] = TestResult(Notifier.Blocked.PERMISSION.message +
                if (permanently) " Android no longer asks, so notification settings were opened." else "",
                openSettings = true)
            if (permanently) openNotificationSettings()
        }
    }

    /** Asks for the permission only when it is needed: on Test, or when a switch is turned on. */
    fun requestPermission(forTest: Kind?) {
        testAfterPermission = forTest
        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun test(kind: Kind) = if (Notifier.needsPermission(ctx)) requestPermission(kind) else runTest(kind)

    fun setSwitch(kind: Kind, on: Boolean) {
        when (kind) {
            Kind.STATE -> { notify = on; settings.notifications = on }
            Kind.BELOW_SMA -> { notifySma = on; settings.notifyBelowSma = on }
            Kind.SHARP_FALL -> { notifySharp = on; settings.notifySharpFall = on }
        }
        if (on && Notifier.needsPermission(ctx)) requestPermission(null)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        ApiKeySection(settings, onSaved)

        Heading("Notifications")
        NotificationSetting(
            text = "Notify when the suggested state changes or an economy flag turns on",
            checked = notify, onChange = { setSwitch(Kind.STATE, it) },
            result = results[Kind.STATE], onTest = { test(Kind.STATE) }, onOpenSettings = ::openNotificationSettings)
        NotificationSetting(
            text = "Notify when S&P 500 is below 200-day average",
            checked = notifySma, onChange = { setSwitch(Kind.BELOW_SMA, it) },
            result = results[Kind.BELOW_SMA], onTest = { test(Kind.BELOW_SMA) }, onOpenSettings = ::openNotificationSettings)
        NotificationSetting(
            text = "Notify sharp daily falls (S&P 500 −2% or worse)",
            checked = notifySharp, onChange = { setSwitch(Kind.SHARP_FALL, it) },
            result = results[Kind.SHARP_FALL], onTest = { test(Kind.SHARP_FALL) }, onOpenSettings = ::openNotificationSettings)
        Text(QuietHours.TEXT, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (held.isNotEmpty()) Text("Held until 08:00: " + held.joinToString("; ") { it.title },
            fontSize = 12.sp, color = palette().warn)
        Text("Test builds a notification by the same rules as real ones from the latest data in the app. Only the " +
            "previous check is simulated (and, if needed, a close below the average, a sharp fall or an economy " +
            "flag). Test notifications start with \"Test:\" and nothing is saved, so real notifications are not " +
            "affected.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val at = settings.lastCheckAt?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).format(CHECK_TIME) }.getOrNull() }
        Text("Last background check (full check twice a day; S&P 500 only at about 03:30 on weekday nights): " +
            if (at == null) "not run yet." else "$at — ${settings.lastCheckResult.orEmpty()}",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Heading("Data sources")
        DataSources()
        CapeControls(settings)
        Text("MarketWatcher describes market conditions. It does not tell you to buy or sell. Not investment advice.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Heading(text: String) =
    Text(text, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })

@Composable
private fun NotificationSetting(
    text: String, checked: Boolean, onChange: (Boolean) -> Unit,
    result: TestResult?, onTest: () -> Unit, onOpenSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = checked, onCheckedChange = onChange)
            Spacer(Modifier.width(12.dp))
            Text(text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onTest) { Text("Test") }
        }
        if (result != null) {
            Text(result.text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp))
            if (result.openSettings) TextButton(onClick = onOpenSettings) { Text("Open notification settings") }
        }
    }
}

@Composable
private fun Sub(t: String) = Text(t, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Body(t: String) =
    Text(t, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun DataSources() {
    val ctx = LocalContext.current
    val dataMissing = remember { BundledData.isMissing(ctx) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Sub("Bundled history (inside the app)")
        if (dataMissing) Text(BundledData.MISSING_TEXT, fontSize = 13.sp, lineHeight = 18.sp, color = palette().warn)
        Body("replay_2007_2026.json: 4,961 trading days from 03.01.2007 to 22.09.2026. Each day has:\n" +
            "• S&P 500 close: 2007–2016 from a public copy of Yahoo Finance daily closes (FRED keeps only about " +
            "10 years; the copy matches FRED within 0.002% where both exist), from 2016 FRED SP500.\n" +
            "• VIX (FRED VIXCLS).\n" +
            "• VIX3M (FRED VXVCLS), which starts in December 2007; the first year is used only to warm up the " +
            "engine, so readings start in 2008.\n" +
            "• Chicago Fed NFCI, the week before, 4 weeks before and 8 weeks before, with the date of its week. This is today's revised history, " +
            "not the values published at the time.\n" +
            "The engine's test compares every day from 2008 with the reference backtest.")
        Body("cape_tr_shiller.json: Robert Shiller's monthly total return CAPE (shillerdata.com, ie_data.xls, " +
            "Data sheet: column O \"TR CAPE\" and column B price), shown from January 2008 to September 2026. " +
            "Shiller's months back to 1881 are kept only to rank values for the top-10% amber rule. The newest months " +
            "are provisional: September 2026 uses the 1 September close and recent inflation is estimated.")
        Sub("Downloaded from FRED and kept on the phone")
        Body("One file per series in the app's private storage, so the app works offline:\n" +
            "• Daily: SP500, VIXCLS, VXVCLS (the last 11 years on first use), DGS2 (2-year Treasury yield) and " +
            "T10Y2Y (10-year minus 2-year yield), both from January 2007. Then only the newest days (60 days back, " +
            "to pick up corrections).\n" +
            "• Weekly NFCI, monthly SAHMREALTIME (real-time Sahm rule), quarterly NCBEILQ027S (market value of US " +
            "nonfinancial corporate equities) and GDP: downloaded in full at each refresh, because they are revised.\n" +
            "Opening the app downloads only if the last download is over 30 minutes old; pull down on Today to " +
            "download now. The background check runs about every 12 hours.")
        Sub("Shiller CAPE updates")
        Body("Weekly, the background check looks for a new ie_data.xls on shillerdata.com and keeps it only if it " +
            "is complete and not older than the data in the app. Between Shiller's monthly updates, the tile shows an " +
            "estimate: the latest official value scaled by the S&P 500 change since that month.")
        Sub("How they are combined")
        Body("Downloaded days are added after 22.09.2026. Sahm rule, 2-year yield, yield curve, Buffett ratio " +
            "(NCBEILQ027S ÷ GDP) and CAPE are added to every day, each value only from the date it was published: " +
            "NFCI 5 days after its week ends, Sahm rule 1 month and 7 days after its month starts, Buffett ratio 75 " +
            "days after its quarter ends. The date under each value is the date of that observation.")
        Body("Several series are copyrighted (S&P 500, VIX, VIX3M and others); Shiller's data comes with no " +
            "warranty. Personal use only.")
    }
}

/** Shows the CAPE data status, with "Check now" (shillerdata.com) and "Import file" (a downloaded ie_data.xls). */
@Composable
private fun CapeControls(settings: Settings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        status = withContext(Dispatchers.IO) {
            val last = runCatching { CapeHistory.load(ctx).lastOfficial?.month }.getOrNull()
            "Latest official CAPE: ${last?.let { "%02d/%d".format(it.monthValue, it.year) } ?: "—"}" +
                (settings.capeCheckResult?.let { " · $it" } ?: "")
        }
    }
    fun finish(text: String) {
        scope.launch {
            val last = withContext(Dispatchers.IO) { runCatching { CapeHistory.load(ctx).lastOfficial?.month }.getOrNull() }
            status = "Latest official CAPE: ${last?.let { "%02d/%d".format(it.monthValue, it.year) } ?: "—"} · $text"
            busy = false
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch { finish(withContext(Dispatchers.IO) { CapeUpdater.import(ctx, uri) }) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (busy) "Checking…" else status.orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch { finish(withContext(Dispatchers.IO) { CapeUpdater.checkOnline(ctx, force = true) }) }
            }) { Text("Check now") }
            TextButton(enabled = !busy, onClick = {
                picker.launch(arrayOf("application/vnd.ms-excel", "application/octet-stream", "*/*"))
            }) { Text("Import file") }
        }
    }
}
