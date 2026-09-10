package com.usage.claudewidget.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.usage.claudewidget.R
import com.usage.claudewidget.ui.MainActivity
import kotlin.math.roundToInt

/** One usage meter row as shown on the opencode.ai Go dashboard. */
data class Meter(
    val pct: Float,
    val pctText: String,
    val resets: String,
)

/** Immutable view state handed to the composable; assembled from UsageStore on each render. */
data class WidgetState(
    val hasKey: Boolean,
    val keyRejected: Boolean,
    val hasData: Boolean,
    val stale: Boolean,
    val fiveHour: Meter,
    val weekly: Meter,
    val monthly: Meter,
) {
    companion object {
        /** Never-throw fallback so provideGlance always has something to render. */
        val EMPTY = WidgetState(
            hasKey = false,
            keyRejected = false,
            hasData = false,
            stale = false,
            fiveHour = Meter(0f, "-", "-"),
            weekly = Meter(0f, "-", "-"),
            monthly = Meter(0f, "-", "-"),
        )

        fun meter(util: Float, resetEpochMs: Long, now: Long): Meter {
            val pct = util.coerceIn(0f, 100f)
            return Meter(
                pct = pct,
                pctText = TimeFmt.pctText(pct),
                resets = TimeFmt.resetsInLong(resetEpochMs, now),
            )
        }
    }
}

private val COMPACT_MAX_WIDTH = 130.dp

@Composable
fun UsageWidgetContent(state: WidgetState) {
    val size = LocalSize.current
    val compact = size.width < COMPACT_MAX_WIDTH
    val context = LocalContext.current

    val tap = if (!state.hasKey || state.keyRejected) {
        actionStartActivity(Intent(context, MainActivity::class.java))
    } else {
        actionRunCallback<RefreshAction>()
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(if (compact) 8.dp else 10.dp)
            .clickable(tap),
    ) {
        when {
            !state.hasKey -> KeyPrompt("Tap to add API key")
            state.keyRejected -> KeyPrompt("Key rejected — tap to fix")
            !state.hasData -> Loading()
            compact -> CompactLayout(state)
            else -> FullLayout(state)
        }
        if (state.hasData && state.stale) StaleDot()
    }
}

/**
 * Dashboard meters rendered from an XML layout so text uses IBM Plex Mono
 * (Glance Text only supports system font families via TypefaceSpan).
 */
@Composable
private fun FullLayout(s: WidgetState) {
    val context = LocalContext.current
    AndroidRemoteViews(buildFullViews(context, s), modifier = GlanceModifier.fillMaxSize())
}

private fun buildFullViews(context: Context, s: WidgetState): RemoteViews {
    val rv = RemoteViews(context.packageName, R.layout.widget_full)
    bindMeter(rv, R.id.m1_pct, R.id.m1_bar, R.id.m1_resets, s.fiveHour)
    bindMeter(rv, R.id.m2_pct, R.id.m2_bar, R.id.m2_resets, s.weekly)
    bindMeter(rv, R.id.m3_pct, R.id.m3_bar, R.id.m3_resets, s.monthly)
    return rv
}

private fun bindMeter(rv: RemoteViews, pctId: Int, barId: Int, resetsId: Int, m: Meter) {
    rv.setTextViewText(pctId, m.pctText)
    rv.setProgressBar(barId, 1000, (m.pct * 10).roundToInt().coerceIn(0, 1000), false)
    rv.setTextViewText(
        resetsId,
        when (m.resets) {
            "-" -> "Reset time unknown"
            "now" -> "Resetting now"
            else -> "Resets in ${m.resets}"
        },
    )
}

/** Small sizes: badge + three tight meter rows, also in IBM Plex Mono. */
@Composable
private fun CompactLayout(s: WidgetState) {
    val context = LocalContext.current
    val rv = RemoteViews(context.packageName, R.layout.widget_compact)
    bindCompactMeter(rv, R.id.c1_pct, R.id.c1_bar, s.fiveHour)
    bindCompactMeter(rv, R.id.c2_pct, R.id.c2_bar, s.weekly)
    bindCompactMeter(rv, R.id.c3_pct, R.id.c3_bar, s.monthly)
    AndroidRemoteViews(rv, modifier = GlanceModifier.fillMaxSize())
}

private fun bindCompactMeter(rv: RemoteViews, pctId: Int, barId: Int, m: Meter) {
    rv.setTextViewText(pctId, m.pctText)
    rv.setProgressBar(barId, 1000, (m.pct * 10).roundToInt().coerceIn(0, 1000), false)
}

/** White badge tile with the official OpenCode "O" mark. */
@Composable
private fun LogoBadge(markWidth: androidx.compose.ui.unit.Dp, markHeight: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = GlanceModifier
            .background(ColorProvider(R.color.go_logo_bg))
            .cornerRadius(3.dp)
            .padding(horizontal = 5.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_opencode_mark),
            contentDescription = "OpenCode",
            modifier = GlanceModifier.width(markWidth).height(markHeight),
        )
    }
}

@Composable
private fun KeyPrompt(message: String) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LogoBadge(24.dp, 30.dp)
        Spacer(GlanceModifier.height(6.dp))
        Text(
            message,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun Loading() {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("…", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
    }
}

@Composable
private fun StaleDot() {
    Box(
        modifier = GlanceModifier.fillMaxSize().padding(2.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            modifier = GlanceModifier
                .size(8.dp)
                .cornerRadius(4.dp)
                .background(ColorProvider(R.color.stale)),
        ) {}
    }
}
