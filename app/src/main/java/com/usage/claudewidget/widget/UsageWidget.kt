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
        val state = readState(context)
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
            fiveHour = Meter(
                pct = store.rollingUtil().coerceAtLeast(0f).roundToInt(),
                resets = TimeFmt.resetsIn(store.rollingReset(), now),
            ),
            weekly = Meter(
                pct = store.weeklyUtil().coerceAtLeast(0f).roundToInt(),
                resets = TimeFmt.resetsIn(store.weeklyReset(), now),
            ),
            monthly = Meter(
                pct = store.monthlyUtil().coerceAtLeast(0f).roundToInt(),
                resets = TimeFmt.resetsIn(store.monthlyReset(), now),
            ),
        )
    }

    companion object {
        suspend fun updateAll(context: Context) = UsageWidget().updateAll(context)
    }
}
