package com.usage.claudewidget.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.usage.claudewidget.data.Const
import java.util.concurrent.TimeUnit

object RefreshScheduler {

    const val KEY_ACCOUNT_ID = "accountId"

    private val networkConstraint =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Periodic 15-minute refresh; survives reboot. Idempotent (KEEP). */
    fun ensurePeriodic(context: Context) {
        val work = PeriodicWorkRequestBuilder<RefreshWorker>(
            Const.REFRESH_INTERVAL_MIN, TimeUnit.MINUTES
        )
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            Const.WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, work
        )
    }

    /**
     * Manual refresh: run once now, ahead of the periodic schedule.
     * Pass [accountId] to refresh only that account; omit to refresh every bound account.
     */
    fun refreshNow(context: Context, accountId: String? = null) {
        val builder = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(networkConstraint)
        if (accountId != null) {
            builder.setInputData(workDataOf(KEY_ACCOUNT_ID to accountId))
        }
        val uniqueName =
            if (accountId != null) "${Const.WORK_NAME}-now-$accountId" else "${Const.WORK_NAME}-now"
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName, ExistingWorkPolicy.REPLACE, builder.build()
        )
    }
}
