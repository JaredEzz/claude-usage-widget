package com.usage.claudewidget.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.usage.claudewidget.auth.LoginActivity
import com.usage.claudewidget.data.Account
import com.usage.claudewidget.data.AccountStorage
import com.usage.claudewidget.widget.UsageWidget
import com.usage.claudewidget.work.ResetNotificationWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launched from a widget tap on an expired account: re-auth that exact account immediately.
        val reAuthId = intent?.getStringExtra(LoginActivity.EXTRA_ACCOUNT_ID)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SetupScreen(reAuthAccountId = reAuthId)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SetupScreen(reAuthAccountId: String?) {
    val context = LocalContext.current
    val storage = remember { AccountStorage.get(context) }
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(storage.listAccounts()) }

    val loginLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        accounts = storage.listAccounts()
    }

    fun signIn(accountId: String?) {
        val intent = Intent(context, LoginActivity::class.java)
        accountId?.let { intent.putExtra(LoginActivity.EXTRA_ACCOUNT_ID, it) }
        loginLauncher.launch(intent)
    }

    // Android 13+ requires this permission before any notification can be posted; only relevant
    // once the user actually turns a "notify at reset" switch on.
    val notifyPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* if denied, the toggle stays on but the notification silently won't post */ }

    fun setNotifyOnReset(accountId: String, enabled: Boolean) {
        storage.setNotifyOnReset(accountId, enabled)
        if (enabled) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
            ) {
                notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            val resetAt = storage.fiveHourReset(accountId)
            if (resetAt > 0L) {
                val label = accounts.find { it.id == accountId }?.label ?: "Claude"
                ResetNotificationWorker.schedule(context, accountId, label, resetAt)
            }
        } else {
            ResetNotificationWorker.cancel(context, accountId)
        }
        accounts = storage.listAccounts()
    }

    // If we were opened to re-auth a specific account, kick that off once.
    androidx.compose.runtime.LaunchedEffect(reAuthAccountId) {
        if (reAuthAccountId != null) signIn(reAuthAccountId)
    }

    // `accounts` (and each row's sign-in status, read from `storage` at composition time) only
    // reflects the current disk/in-memory state as of the last recomposition -- Compose has no
    // reason to recompose this screen just because credentials changed via a route it doesn't
    // observe (e.g. a login driven from outside this Activity's own ActivityResultLauncher, such
    // as `adb shell am start`). Re-read on every resume so returning to this screen never shows
    // stale "signed out" state for an account that's actually signed in.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) accounts = storage.listAccounts()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Antigravity Usage", style = MaterialTheme.typography.headlineSmall)
        Text("Google Models & Claude Models (matching agy usage)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (accounts.isEmpty()) {
            Text("No Google accounts connected yet.", style = MaterialTheme.typography.bodyMedium)
        }

        accounts.forEach { account ->
            AccountRow(
                account = account,
                storage = storage,
                needsLogin = !storage.isLoggedIn(account.id),
                notifyEnabled = storage.notifyOnReset(account.id),
                onSignIn = { signIn(account.id) },
                onRefreshNow = {
                    scope.launch {
                        RefreshScheduler.refreshNow(context, account.id)
                        UsageWidget.updateAll(context)
                        accounts = storage.listAccounts()
                    }
                },
                onSignOut = {
                    ResetNotificationWorker.cancel(context, account.id)
                    storage.removeAccount(account.id)
                    accounts = storage.listAccounts()
                    scope.launch { UsageWidget.updateAll(context) }
                },
                onToggleNotify = { setNotifyOnReset(account.id, it) },
            )
        }

        Button(onClick = { signIn(null) }) {
            Text("Sign in with Google")
        }

        OutlinedButton(onClick = { requestBatteryExemption(context) }) {
            Text("Disable battery optimization")
        }
    }
}

@androidx.compose.runtime.Composable
private fun AccountRow(
    account: Account,
    storage: AccountStorage,
    needsLogin: Boolean,
    notifyEnabled: Boolean,
    onSignIn: () -> Unit,
    onRefreshNow: () -> Unit,
    onSignOut: () -> Unit,
    onToggleNotify: (Boolean) -> Unit,
) {
    val blueColor = androidx.compose.ui.graphics.Color(0xFF2563EB)
    val orangeColor = androidx.compose.ui.graphics.Color(0xFFF97316)

    val g5hUtil = storage.gemini5hUtil(account.id).coerceAtLeast(0f).toInt()
    val gwkUtil = storage.geminiWeeklyUtil(account.id).coerceAtLeast(0f).toInt()
    val c5hUtil = storage.claude5hUtil(account.id).coerceAtLeast(0f).toInt()
    val cwkUtil = storage.claudeWeeklyUtil(account.id).coerceAtLeast(0f).toInt()

    Surface(
        tonalElevation = 2.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    account.label + if (needsLogin) " (signed out)" else "",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onRefreshNow) { Text("Refresh") }
                    TextButton(onClick = onSignIn) {
                        Text(if (needsLogin) "Sign in" else "Re-sign in")
                    }
                    TextButton(onClick = onSignOut) { Text("Sign out") }
                }
            }

            if (!needsLogin && storage.hasSnapshot(account.id)) {
                // Gemini meter (Google Blue)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Gemini: ", style = MaterialTheme.typography.labelLarge, color = blueColor)
                    Text("5H $g5hUtil%  |  1W $gwkUtil%", style = MaterialTheme.typography.bodyMedium)
                }

                // Claude meter (Orange)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Claude: ", style = MaterialTheme.typography.labelLarge, color = orangeColor)
                    Text("5H $c5hUtil%  |  1W $cwkUtil%", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Notify at 5H reset", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = notifyEnabled, onCheckedChange = onToggleNotify)
            }
        }
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
