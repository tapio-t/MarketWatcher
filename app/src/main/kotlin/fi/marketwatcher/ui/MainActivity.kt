package fi.marketwatcher.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.marketwatcher.data.Redact
import fi.marketwatcher.data.Settings
import fi.marketwatcher.data.SignalRepository
import fi.marketwatcher.work.DailyCheckWorker
import fi.marketwatcher.work.EarlyCheckWorker
import fi.marketwatcher.work.NotificationGate
import fi.marketwatcher.work.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DailyCheckWorker.schedule(this)
        EarlyCheckWorker.schedule(this)   // S&P 500 only, weekday nights 20:30 New York time
        // High-importance channels; replaces the old default-importance one. Notification permission
        // is asked for only when a notification switch is turned on or Test is tapped (Settings).
        Notifier.ensureChannels(this)
        val repo = SignalRepository(this)
        val settings = Settings(this)
        setContent { AppTheme { App(repo, settings) } }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
}

// ---------------------------------------------------------------- app shell

private enum class Tab(val title: String) { TODAY("Today"), HISTORY("History"), SETTINGS("Settings") }

@Composable
private fun App(repo: SignalRepository, settings: Settings) {
    var tab by rememberSaveable { mutableStateOf(if (!settings.hasApiKey) Tab.SETTINGS else Tab.TODAY) }
    var result by remember { mutableStateOf<SignalRepository.Result?>(null) }
    var loading by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Chart arrays are built once per data load, not per frame.
    val chart = remember(result?.history) { result?.history?.takeIf { it.size > 1 }?.let { ChartData(it) } }

    var checkedAt by remember { mutableLongStateOf(settings.lastDownloadAt) }
    // Counts finished downloads, so a slower cache re-read never replaces a newer download.
    var downloads by remember { mutableIntStateOf(0) }

    /** Downloads from FRED now (pull to refresh, new key) — the 30-minute rule doesn't apply. */
    fun refresh() {
        if (loading) return
        loading = true          // set before launching, so two quick calls can't both start
        scope.launch {
            try {
                result = withContext(Dispatchers.IO) { repo.refresh() }
                downloads++
                checkedAt = settings.lastDownloadAt
            } catch (e: Exception) {
                snackbar.showSnackbar(Redact.text(e.message ?: "Could not reach FRED."))
            }
            loading = false
        }
    }
    /** Opening or returning to the app: download only if the last download is over 30 minutes old. */
    fun refreshIfDue() {
        if (settings.hasApiKey && settings.downloadDue(30)) refresh()
        else if (!loading) scope.launch {
            // Not due: re-read the cache, which the early check may have updated in the background.
            val before = downloads
            val cached = withContext(Dispatchers.IO) { runCatching { repo.fromCache() }.getOrNull() }
            if (cached != null && !loading && downloads == before) result = cached
        }
    }
    LaunchedEffect(Unit) {
        // bundled 2007–2026 history + cached FRED data: shown at once, works offline and without a key
        val cached = withContext(Dispatchers.IO) { runCatching { repo.fromCache() }.getOrNull() }
        if (result == null) result = cached     // never replace a fresher download that finished first
    }

    // History is a landscape screen; Today and Settings follow the device.
    val activity = LocalActivity.current
    // Every start of the screen (opening the app, or coming back to it) checks the 30-minute rule.
    val lifecycle = (activity as? LifecycleOwner)?.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                checkedAt = settings.lastDownloadAt   // includes downloads by the background check
                refreshIfDue()
                // Inside 08:00–18:00: show any notifications held overnight now, not only at 08:00.
                activity?.applicationContext?.let { app -> scope.launch(Dispatchers.IO) { runCatching { NotificationGate.flushAndRecord(app) } } }
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(tab) {
        activity?.requestedOrientation =
            if (tab == Tab.HISTORY) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!landscape) NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t },
                        icon = { NavIcon(t) }, label = { Text(t.title) })
                }
            }
        },
    ) { pad ->
        Row(Modifier.padding(pad).fillMaxSize()) {
            if (landscape) NavigationRail {
                Spacer(Modifier.weight(1f))
                Tab.entries.forEach { t ->
                    NavigationRailItem(selected = tab == t, onClick = { tab = t },
                        icon = { NavIcon(t) }, label = { Text(t.title) })
                }
                Spacer(Modifier.weight(1f))
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    Tab.TODAY -> TodayScreen(result?.today, result?.price, chart, settings.hasApiKey, loading, checkedAt) { refresh() }
                    Tab.HISTORY -> HistoryScreen(chart)
                    Tab.SETTINGS -> SettingsScreen(settings, result?.today ?: result?.history?.lastOrNull(), result?.price) { message ->
                        tab = Tab.TODAY
                        scope.launch { snackbar.showSnackbar(message) }
                        refresh()
                    }
                }
            }
        }
    }
}

/** Simple line icons drawn in code, so no icon library is needed. */
@Composable
private fun NavIcon(t: Tab) {
    val c = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val s = size.minDimension
        val st = Stroke(width = s * 0.08f, cap = StrokeCap.Round)
        when (t) {
            Tab.TODAY -> drawPath(Path().apply {
                moveTo(s * .12f, s * .48f); lineTo(s * .5f, s * .16f); lineTo(s * .88f, s * .48f)
                lineTo(s * .88f, s * .86f); lineTo(s * .12f, s * .86f); close()
            }, c, style = st)
            Tab.HISTORY -> drawPath(Path().apply {
                moveTo(s * .1f, s * .78f); lineTo(s * .36f, s * .48f); lineTo(s * .56f, s * .62f); lineTo(s * .9f, s * .22f)
            }, c, style = st)
            Tab.SETTINGS -> {
                val cx = s / 2f
                drawCircle(c, radius = s * .14f, center = Offset(cx, cx), style = st)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    drawLine(c,
                        Offset(cx + (s * .28f * cos(a)).toFloat(), cx + (s * .28f * sin(a)).toFloat()),
                        Offset(cx + (s * .42f * cos(a)).toFloat(), cx + (s * .42f * sin(a)).toFloat()),
                        strokeWidth = s * .08f, cap = StrokeCap.Round)
                }
            }
        }
    }
}
