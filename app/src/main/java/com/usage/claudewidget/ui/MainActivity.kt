package com.usage.claudewidget.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.usage.claudewidget.R
import com.usage.claudewidget.data.AuthState
import com.usage.claudewidget.data.FetchResult
import com.usage.claudewidget.data.UsageRepository
import com.usage.claudewidget.data.UsageStore
import com.usage.claudewidget.widget.TimeFmt
import com.usage.claudewidget.widget.UsageWidget
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Supports silent key provisioning: `am start ... --es api_key sk-...`
        val injectedKey = intent?.getStringExtra(EXTRA_API_KEY)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalTextStyle provides TextStyle(fontFamily = plexMono())
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        SetupScreen(injectedKey = injectedKey)
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_API_KEY = "api_key"
    }
}

private fun plexMono() = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_mono_bold, FontWeight.Bold),
)

@Composable
private fun SetupScreen(injectedKey: String?) {
    val context = LocalContext.current
    val store = remember { UsageStore.get(context) }
    val scope = rememberCoroutineScope()
    var keyValue by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    // Bumped after each refresh so the status card re-reads the store.
    var refreshTick by remember { mutableStateOf(0L) }

    fun refreshNow() {
        scope.launch {
            status = "Refreshing…"
            val result = UsageRepository(context).refresh()
            status = when (result) {
                is FetchResult.Success -> "OK"
                is FetchResult.NeedsLogin -> "Key missing or rejected"
                is FetchResult.Soft -> "Error: ${result.reason}"
            }
            UsageWidget.updateAll(context)
            refreshTick = System.nanoTime()
        }
    }

    // Silent provisioning path (adb). Saves the key, then fetches immediately.
    LaunchedEffect(injectedKey) {
        if (!injectedKey.isNullOrBlank()) {
            store.setApiKey(injectedKey.trim())
            store.setAuthState(AuthState.OK)
            refreshNow()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("OpenCode Go Usage", style = MaterialTheme.typography.headlineSmall)
        Text(
            "5-hour / weekly / monthly limits from opencode.ai",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(store, status, refreshTick)

        OutlinedTextField(
            value = keyValue,
            onValueChange = { keyValue = it },
            label = { Text("OpenCode Go API key (sk-...)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    store.setApiKey(keyValue)
                    store.setAuthState(AuthState.OK)
                    keyValue = ""
                    refreshNow()
                },
                enabled = keyValue.isNotBlank(),
            ) {
                Text("Save key")
            }
            OutlinedButton(onClick = { refreshNow() }) {
                Text("Refresh")
            }
        }

        OutlinedButton(onClick = { requestBatteryExemption(context) }) {
            Text("Disable battery optimization")
        }
    }
}

@Composable
private fun StatusCard(store: UsageStore, status: String?, refreshTick: Long) {
    val fetched = store.fetchedAt()
    val fetchedTxt = if (fetched > 0L) {
        Instant.ofEpochMilli(fetched).atZone(ZoneId.systemDefault()).format(
            DateTimeFormatter.ofPattern("h:mm a")
        )
    } else "never"

    val authTxt = when {
        !store.hasKey() -> "No key saved"
        store.authState() == AuthState.NEEDS_LOGIN -> "Key rejected"
        else -> "Signed in"
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(authTxt, style = MaterialTheme.typography.titleMedium)
        Text(
            "Last fetch: $fetchedTxt" + (status?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (store.hasSnapshot()) {
            UsageMeter(
                label = "5-hour Usage",
                pct = store.rollingUtil().coerceAtLeast(0f),
                resets = TimeFmt.resetsInLong(store.rollingReset()),
            )
            UsageMeter(
                label = "Weekly Usage",
                pct = store.weeklyUtil().coerceAtLeast(0f),
                resets = TimeFmt.resetsInLong(store.weeklyReset()),
            )
            UsageMeter(
                label = "Monthly Usage",
                pct = store.monthlyUtil().coerceAtLeast(0f),
                resets = TimeFmt.resetsInLong(store.monthlyReset()),
            )
        }
        store.lastError()?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun UsageMeter(label: String, pct: Float, resets: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                TimeFmt.pctText(pct),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { pct.coerceIn(0f, 100f) / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            when (resets) {
                "-" -> "Reset time unknown"
                "now" -> "Resetting now"
                else -> "Resets in $resets"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun requestBatteryExemption(context: Context) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }
}
