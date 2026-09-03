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

        // No account bound yet: show sign-in prompt.
        if (accountId == null) {
            return WidgetState(
                accountId = null,
                needsLogin = true,
                hasData = false,
                gemini5hPct = 0,
                gemini5hElapsedPct = 0,
                gemini5hResets = "-",
                geminiWeeklyPct = 0,
                geminiWeeklyElapsedPct = 0,
                geminiWeeklyResets = "-",
                claude5hPct = 0,
                claude5hElapsedPct = 0,
                claude5hResets = "-",
                claudeWeeklyPct = 0,
                claudeWeeklyElapsedPct = 0,
                claudeWeeklyResets = "-",
                stale = false,
            )
        }

        return WidgetState(
            accountId = accountId,
            needsLogin = !storage.isLoggedIn(accountId) ||
                storage.authState(accountId) == AuthState.NEEDS_LOGIN,
            hasData = storage.hasSnapshot(accountId),
            gemini5hPct = storage.gemini5hUtil(accountId).coerceAtLeast(0f).roundToInt(),
            gemini5hElapsedPct = TimeFmt.elapsedPct(storage.gemini5hReset(accountId), TimeFmt.FIVE_HOUR_MS, now),
            gemini5hResets = TimeFmt.resetsSummary(storage.gemini5hReset(accountId), now),
            geminiWeeklyPct = storage.geminiWeeklyUtil(accountId).coerceAtLeast(0f).roundToInt(),
            geminiWeeklyElapsedPct = TimeFmt.elapsedPct(storage.geminiWeeklyReset(accountId), TimeFmt.SEVEN_DAY_MS, now),
            geminiWeeklyResets = TimeFmt.resetsSummary(storage.geminiWeeklyReset(accountId), now),
            claude5hPct = storage.claude5hUtil(accountId).coerceAtLeast(0f).roundToInt(),
            claude5hElapsedPct = TimeFmt.elapsedPct(storage.claude5hReset(accountId), TimeFmt.FIVE_HOUR_MS, now),
            claude5hResets = TimeFmt.resetsSummary(storage.claude5hReset(accountId), now),
            claudeWeeklyPct = storage.claudeWeeklyUtil(accountId).coerceAtLeast(0f).roundToInt(),
            claudeWeeklyElapsedPct = TimeFmt.elapsedPct(storage.claudeWeeklyReset(accountId), TimeFmt.SEVEN_DAY_MS, now),
            claudeWeeklyResets = TimeFmt.resetsSummary(storage.claudeWeeklyReset(accountId), now),
            stale = TimeFmt.isStale(storage.fetchedAt(accountId), now),
        )
    }

    companion object {
        suspend fun updateAll(context: Context) = UsageWidget().updateAll(context)
    }
}
