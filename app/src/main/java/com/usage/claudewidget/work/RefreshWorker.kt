package com.usage.claudewidget.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.usage.claudewidget.data.AccountStorage
import com.usage.claudewidget.data.FetchResult
import com.usage.claudewidget.data.UsageRepository
import com.usage.claudewidget.widget.UsageWidget

/** Fetches usage in the background for every bound account and pushes results into the widgets. */
class RefreshWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val storage = AccountStorage.get(applicationContext)
        // A scoped one-time job carries a single accountId; the periodic job refreshes all bound.
        val single = inputData.getString(RefreshScheduler.KEY_ACCOUNT_ID)
        val accountIds = if (single != null) setOf(single) else storage.boundAccountIds()

        if (accountIds.isEmpty()) return Result.success()

        // Refresh each account sequentially. A Soft failure on any one triggers a retry, but must
        // not block the others from refreshing first.
        var anySoft = false
        for (accountId in accountIds) {
            val result = UsageRepository(applicationContext, accountId).refresh()
            if (result is FetchResult.Soft) anySoft = true
        }
        // Snapshots + authState are already persisted by each repository; just re-render.
        UsageWidget.updateAll(applicationContext)
        return if (anySoft) Result.retry() else Result.success()
    }
}
