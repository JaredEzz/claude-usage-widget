package com.usage.claudewidget.widget

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.usage.claudewidget.data.UsageRepository
import com.usage.claudewidget.data.UsageStore
import com.usage.claudewidget.ui.MainActivity

/** Tap action when the widget is healthy: fetch fresh usage, then re-render. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val store = UsageStore.get(context)
        if (!store.hasKey()) {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        UsageRepository(context).refresh()
        UsageWidget.updateAll(context)
    }
}
