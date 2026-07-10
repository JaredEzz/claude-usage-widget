package com.usage.claudewidget.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import com.usage.claudewidget.data.AccountStorage
import com.usage.claudewidget.data.AuthState
import kotlin.math.roundToInt

class UsageWidget : GlanceAppWidget() {

    // Two reusable buckets; Glance maps any real size to the nearest one.
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(60.dp, 60.dp),    // Compact (~1x1)
            DpSize(180.dp, 110.dp),  // Full
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = readState(context, id)
        provideContent {
            GlanceTheme {
                UsageWidgetContent(state)
            }
        }
    }

    private suspend fun readState(context: Context, id: GlanceId): WidgetState {
        val storage = AccountStorage.get(context)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val accountId = storage.accountIdFor(appWidgetId)
        val now = System.currentTimeMillis()

        // No account bound yet (defensive; Configure normally runs first): show sign-in prompt.
        if (accountId == null) {
            return WidgetState(
                accountId = null,
                needsLogin = true,
                hasData = false,
                fiveHourPct = 0,
                fiveHourResets = "-",
                sevenDayPct = 0,
                sevenDayResets = "-",
                stale = false,
            )
        }

        return WidgetState(
            accountId = accountId,
            needsLogin = !storage.isLoggedIn(accountId) ||
                storage.authState(accountId) == AuthState.NEEDS_LOGIN,
            hasData = storage.hasSnapshot(accountId),
            fiveHourPct = storage.fiveHourUtil(accountId).coerceAtLeast(0f).roundToInt(),
            fiveHourResets = TimeFmt.resetsSummary(storage.fiveHourReset(accountId), now),
            sevenDayPct = storage.sevenDayUtil(accountId).coerceAtLeast(0f).roundToInt(),
            sevenDayResets = TimeFmt.resetsSummary(storage.sevenDayReset(accountId), now),
            stale = TimeFmt.isStale(storage.fetchedAt(accountId), now),
        )
    }

    companion object {
        suspend fun updateAll(context: Context) = UsageWidget().updateAll(context)
    }
}
