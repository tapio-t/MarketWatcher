package fi.marketwatcher.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.marketwatcher.data.FredClient
import fi.marketwatcher.data.FredException
import fi.marketwatcher.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val OLD_APP = "fi.dipwatch"
private val KEY_FORMAT = Regex("^[a-z0-9]{32}$")

private fun isInstalled(ctx: Context, pkg: String): Boolean = try {
    if (Build.VERSION.SDK_INT >= 33) ctx.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
    else @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(pkg, 0)
    true
} catch (e: PackageManager.NameNotFoundException) { false }

private fun formatError(k: String): String? = when {
    k.isEmpty() || KEY_FORMAT.matches(k) -> null
    k.length != 32 -> "This key has ${k.length} characters; FRED keys have 32."
    else -> "Use only lowercase letters a–z and digits."
}

private data class KeyInfo(val tail: String?, val unreadable: Boolean, val migratedAt: Long?, val oldAppInstalled: Boolean)

/**
 * FRED API key in Settings. The full key is never shown again after saving and is never put
 * into saved UI state: only its last 4 characters are displayed.
 */
@Composable
internal fun ApiKeySection(settings: Settings, onSaved: (message: String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val warn = palette().warn
    val err = MaterialTheme.colorScheme.error

    var info by remember { mutableStateOf<KeyInfo?>(null) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }          // plain remember: never written to saved state
    var reveal by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    // Keystore work and the package check run off the main thread; re-run whenever Settings opens.
    LaunchedEffect(Unit) {
        info = withContext(Dispatchers.IO) {
            settings.migrateKey()
            KeyInfo(settings.apiKeyTail(), settings.keyWasUnreadable, settings.keyMigratedAt, isInstalled(ctx, OLD_APP))
        }
    }

    fun save() {
        val k = draft.trim()
        if (!KEY_FORMAT.matches(k) || checking) return
        checking = true; error = null
        scope.launch {
            val check = withContext(Dispatchers.IO) { runCatching { FredClient(k).verifyKey() } }.exceptionOrNull()
            if (check is FredException && check.keyRejected) {
                error = "FRED rejected this key. Check it at fredaccount.stlouisfed.org/apikeys."
                checking = false
                return@launch
            }
            val saved = withContext(Dispatchers.IO) { runCatching { settings.saveApiKey(k) } }
            checking = false
            if (saved.isFailure) {
                error = "Couldn't save the key securely on this phone. Try again."
                return@launch
            }
            draft = ""; reveal = false; editing = false
            info = info?.copy(tail = k.takeLast(4), unreadable = false) ?: KeyInfo(k.takeLast(4), false, null, false)
            onSaved(if (check == null) "API key saved and encrypted. Refreshing data…"
                    else "API key saved and encrypted. FRED couldn't be reached to check it; it's checked at the next refresh.")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("FRED API key", fontWeight = FontWeight.SemiBold)
        val i = info

        if (i?.unreadable == true) Note(warn, "Enter your API key again",
            "The saved key couldn't be read on this phone, for example after restoring from a backup or a security reset. It has been removed.")

        if (i?.oldAppInstalled == true) {
            Note(warn, "Old Dip Watch app found",
                "Dip Watch still holds your API key in plain text. Uninstalling it deletes that copy.") {
                TextButton(onClick = {
                    runCatching {
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", OLD_APP, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Uninstall Dip Watch") }
            }
        }

        val tail = i?.tail
        if (tail != null && !editing) {
            Column(
                Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Saved key", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("•••• •••• $tail", fontSize = 17.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.semantics { contentDescription = "Saved key ending in ${tail.toList().joinToString(" ")}" })
                Text("Encrypted on this phone. Not included in backups.", fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, color = palette().calm)
                val migrated = i?.migratedAt
                if (migrated != null) {
                    val recent = System.currentTimeMillis() - migrated < TimeUnit.DAYS.toMillis(14)
                    Text("Plain-text copy removed." +
                        if (recent) " The next nightly Google backup replaces the old backup." else "",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (confirmRemove) {
                Column(
                    Modifier.fillMaxWidth().border(BorderStroke(1.dp, err.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Remove the saved key?", fontWeight = FontWeight.SemiBold)
                    Text("MarketWatcher stops downloading new data and background checks pause until you add a key " +
                        "again. You can still browse saved data.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { settings.removeApiKey() }
                                info = info?.copy(tail = null)
                                confirmRemove = false
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = err, contentColor = MaterialTheme.colorScheme.onError)) {
                            Text("Remove")
                        }
                        TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { editing = true; draft = ""; error = null }) { Text("Replace key") }
                    TextButton(onClick = { confirmRemove = true }) { Text("Remove key", color = err) }
                }
            }
        } else if (i != null) {
            // While the key can be typed or shown, keep it out of screenshots and the Recents preview.
            val activity = LocalActivity.current
            DisposableEffect(activity) {
                activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { v -> draft = v.filterNot { it.isWhitespace() }.take(40); error = null },
                label = { Text(if (tail != null) "New FRED API key" else "FRED API key") },
                singleLine = true,
                enabled = !checking,
                isError = error != null,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                // Password keyboard: no suggestions, autocorrect or learning of the key.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false,
                    imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                trailingIcon = { TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") } },
                supportingText = {
                    Row(Modifier.fillMaxWidth()) {
                        Text(error ?: "32 characters, letters a–z and digits. Paste from fredaccount.stlouisfed.org.",
                            modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text("${draft.length}/32", fontFamily = FontFamily.Monospace)
                    }
                },
                modifier = Modifier.fillMaxWidth()
                    .semantics { if (!reveal) password() }
                    .onFocusChanged { f -> if (!f.isFocused && error == null) error = formatError(draft.trim()) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { save() }, enabled = KEY_FORMAT.matches(draft.trim()) && !checking) {
                    if (checking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("Checking with FRED…")
                    } else Text("Save and refresh")
                }
                if (tail != null) TextButton(onClick = { editing = false; draft = ""; error = null }, enabled = !checking) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun Note(color: Color, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, color = color, fontSize = 14.sp)
        Text(body, fontSize = 13.sp)
        action?.invoke()
    }
}
