package com.usage.claudewidget.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import android.content.Intent
import android.graphics.Color as AndroidColor
import com.usage.claudewidget.R
import com.usage.claudewidget.ui.MainActivity

/** One usage meter row as shown on the opencode.ai Go dashboard. */
data class Meter(
    val pct: Int,
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
)

private val COMPACT_MAX_WIDTH = 130.dp

// The dashboard's blue progress bars.
private val goBlue = ColorProvider(R.color.go_accent)
private fun barTrack() = ColorProvider(R.color.bar_track)

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
            .padding(if (compact) 8.dp else 12.dp)
            .clickable(tap),
    ) {
        when {
            !state.hasKey -> KeyPrompt(compact, "Tap to add API key")
            state.keyRejected -> KeyPrompt(compact, "Key rejected — tap to fix")
            !state.hasData -> Loading()
            compact -> CompactLayout(state)
            else -> FullLayout(state)
        }
        if (state.hasData && state.stale) StaleDot()
    }
}

/** Dashboard-style layout: label + pct, blue bar, "Resets in ..." underneath. */
@Composable
private fun FullLayout(s: WidgetState) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GoLogo(13.sp)
            Spacer(GlanceModifier.width(6.dp))
            Text(
                "OpenCode Go",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                ),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        DashboardMeter("5-hour Usage", s.fiveHour)
        Spacer(GlanceModifier.height(7.dp))
        DashboardMeter("Weekly Usage", s.weekly)
        Spacer(GlanceModifier.height(7.dp))
        DashboardMeter("Monthly Usage", s.monthly)
    }
}

@Composable
private fun DashboardMeter(label: String, m: Meter) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                ),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                "${m.pct}%",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                ),
            )
        }
        Spacer(GlanceModifier.height(3.dp))
        Bar(m.pct)
        Spacer(GlanceModifier.height(2.dp))
        Text(
            if (m.resets == "now") "Resetting now" else "Resets in ${m.resets}",
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 10.sp,
            ),
        )
    }
}

/** Small sizes: logo + three tight label/pct/bar rows. */
@Composable
private fun CompactLayout(s: WidgetState) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        GoLogo(10.sp)
        Spacer(GlanceModifier.height(4.dp))
        CompactMeter("5H", s.fiveHour.pct)
        Spacer(GlanceModifier.height(3.dp))
        CompactMeter("1W", s.weekly.pct)
        Spacer(GlanceModifier.height(3.dp))
        CompactMeter("30D", s.monthly.pct)
    }
}

@Composable
private fun CompactMeter(label: String, pct: Int) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                label,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                ),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                "$pct%",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                ),
            )
        }
        Spacer(GlanceModifier.height(2.dp))
        Bar(pct)
    }
}

@Composable
private fun Bar(pct: Int) {
    LinearProgressIndicator(
        progress = (pct.coerceIn(0, 100)) / 100f,
        modifier = GlanceModifier.fillMaxWidth().height(5.dp).cornerRadius(2.dp),
        color = goBlue,
        backgroundColor = barTrack(),
    )
}

/** White "GO" badge on a dark tile, like the dashboard logo. */
@Composable
private fun GoLogo(textSize: androidx.compose.ui.unit.TextUnit) {
    Box(
        modifier = GlanceModifier
            .background(ColorProvider(AndroidColor.WHITE))
            .cornerRadius(3.dp)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "GO",
            style = TextStyle(
                color = ColorProvider(AndroidColor.BLACK),
                fontWeight = FontWeight.Bold,
                fontSize = textSize,
            ),
        )
    }
}

@Composable
private fun KeyPrompt(compact: Boolean, message: String) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GoLogo(if (compact) 12.sp else 16.sp)
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
