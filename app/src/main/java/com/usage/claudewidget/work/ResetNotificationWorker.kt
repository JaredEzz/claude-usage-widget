package com.usage.claudewidget.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.usage.claudewidget.R
import java.util.concurrent.TimeUnit

/**
 * Fires once, at the moment an account's 5-hour usage window is expected to reset, and posts a
 * local notification. Rescheduled (via [schedule]) on every successful refresh so it always
 * tracks the latest known reset time; [cancel] drops it when the user turns the setting off or
 * signs the account out.
 */
class ResetNotificationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val label = inputData.getString(KEY_LABEL) ?: "Claude"
        postNotification(applicationContext, label)
        return Result.success()
    }

    private fun postNotification(context: Context, label: String) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            return // Permission was revoked after scheduling; nothing we can do here.
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lobster)
            .setContentTitle("$label usage reset")
            .setContentText("Your 5-hour Antigravity quota has reset.")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(label.hashCode(), notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Usage reset", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        private const val CHANNEL_ID = "usage_reset"
        private const val KEY_LABEL = "label"

        private fun workName(accountId: String) = "reset-notify-$accountId"

        /** (Re)schedules a one-shot notification for [accountId] at [resetAtEpochMs]. */
        fun schedule(context: Context, accountId: String, label: String, resetAtEpochMs: Long) {
            val delay = resetAtEpochMs - System.currentTimeMillis()
            if (delay <= 0L) return
            val request = OneTimeWorkRequestBuilder<ResetNotificationWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_LABEL to label))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(accountId), ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, accountId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(accountId))
        }
    }
}
