package com.usage.claudewidget.widget

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import com.usage.claudewidget.data.AccountStorage
import com.usage.claudewidget.data.UsageRepository

/** Tap action when the widget is healthy: refresh only the account bound to THIS widget. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        val accountId = AccountStorage.get(context).accountIdFor(appWidgetId)
        if (accountId == null) {
            // Unbound (shouldn't happen — Configure gates widget placement): send user to config.
            val intent = Intent(context, WidgetConfigActivity::class.java)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }
        UsageRepository(context, accountId).refresh()
        UsageWidget.updateAll(context)
    }
}
