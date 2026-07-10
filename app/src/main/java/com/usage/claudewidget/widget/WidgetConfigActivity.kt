package com.usage.claudewidget.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.usage.claudewidget.auth.LoginActivity
import com.usage.claudewidget.data.AccountStorage
import com.usage.claudewidget.work.RefreshScheduler

/**
 * Picks which Claude account a placed widget instance shows. Launched by Android when a widget is
 * added (android:configure) or reconfigured. Lists existing accounts + an "Add account" row.
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Default to CANCELED so backing out leaves the widget un-placed.
        setResult(
            RESULT_CANCELED,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ConfigScreen(onBind = ::bindAndFinish)
                }
            }
        }
    }

    private fun bindAndFinish(accountId: String) {
        val storage = AccountStorage.get(this)
        storage.bindWidget(appWidgetId, accountId)
        // Fetch this account immediately, then re-render the widget.
        RefreshScheduler.refreshNow(this, accountId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}

@androidx.compose.runtime.Composable
private fun ConfigScreen(onBind: (String) -> Unit) {
    val context = LocalContext.current
    val storage = remember { AccountStorage.get(context) }
    var accounts by remember { mutableStateOf(storage.listAccounts()) }

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val accountId = result.data?.getStringExtra(LoginActivity.EXTRA_ACCOUNT_ID)
        if (result.resultCode == android.app.Activity.RESULT_OK && accountId != null) {
            onBind(accountId)
        } else {
            accounts = storage.listAccounts()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Choose an account", style = MaterialTheme.typography.headlineSmall)

        accounts.forEach { account ->
            Text(
                account.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBind(account.id) }
                    .padding(vertical = 12.dp),
            )
        }

        Text(
            "+ Add account",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    loginLauncher.launch(Intent(context, LoginActivity::class.java))
                }
                .padding(vertical = 12.dp),
        )
    }
}
