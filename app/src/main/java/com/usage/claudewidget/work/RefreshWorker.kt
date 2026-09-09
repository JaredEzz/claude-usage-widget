package com.usage.claudewidget.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.usage.claudewidget.data.UsageRepository
import com.usage.claudewidget.widget.UsageWidget

/** Fetches OpenCode Go usage in the background and pushes the result into the widgets. */
class RefreshWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = UsageRepository(applicationContext).refresh()
        // Snapshot + auth state are persisted by the repository; just re-render.
        UsageWidget.updateAll(applicationContext)
        return when (result) {
            is com.usage.claudewidget.data.FetchResult.Soft -> Result.retry()
            else -> Result.success()
        }
    }
}
