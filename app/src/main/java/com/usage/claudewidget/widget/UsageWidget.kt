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
import com.usage.claudewidget.data.AuthState
import com.usage.claudewidget.data.UsageStore
import com.usage.claudewidget.work.RefreshScheduler

class UsageWidget : GlanceAppWidget() {

    // Two reusable buckets; Glance maps any real size to the nearest one.
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(60.dp, 60.dp),    // Compact (~1x1)
            DpSize(180.dp, 110.dp),  // Full
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Render FAST from the saved snapshot only. Never do network I/O here:
        // a blocking fetch on the render path exceeds the launcher's bind
        // limit and the widget shows the system "Can't load widget" placeholder.
        // Fresh data arrives via the periodic worker, tap-to-refresh, and the
        // one-shot refresh kicked below (all re-render when done).
        val state = try {
            if (UsageStore.get(context).hasKey()) {
                RefreshScheduler.refreshNow(context)
            }
            readState(context)
        } catch (_: Exception) {
            WidgetState.EMPTY
        }
        provideContent {
            GlanceTheme {
                UsageWidgetContent(state)
            }
        }
    }

    private fun readState(context: Context): WidgetState {
        val store = UsageStore.get(context)
        val now = System.currentTimeMillis()

        return WidgetState(
            hasKey = store.hasKey(),
            keyRejected = store.hasKey() && store.authState() == AuthState.NEEDS_LOGIN,
            hasData = store.hasSnapshot(),
            stale = TimeFmt.isStale(store.fetchedAt(), now),
            fiveHour = WidgetState.meter(store.rollingUtil().coerceAtLeast(0f), store.rollingReset(), now),
            weekly = WidgetState.meter(store.weeklyUtil().coerceAtLeast(0f), store.weeklyReset(), now),
            monthly = WidgetState.meter(store.monthlyUtil().coerceAtLeast(0f), store.monthlyReset(), now),
        )
    }

    companion object {
        suspend fun updateAll(context: Context) = UsageWidget().updateAll(context)
    }
}
