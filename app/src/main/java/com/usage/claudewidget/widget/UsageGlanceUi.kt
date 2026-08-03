package com.usage.claudewidget.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import androidx.glance.LocalContext
import android.content.Intent
import com.usage.claudewidget.R
import com.usage.claudewidget.auth.LoginActivity
import com.usage.claudewidget.ui.MainActivity

/** Immutable view state handed to the composable; assembled from AccountStorage on each render. */
data class WidgetState(
    val accountId: String?,
    val needsLogin: Boolean,
    val hasData: Boolean,
    val fiveHourPct: Int,
    val fiveHourResets: String,
    val sevenDayPct: Int,
    val sevenDayResets: String,
    val scopedLabel: String?,     // e.g. "Fable"; null when the account has no model-scoped limit
    val scopedPct: Int,
    val scopedResets: String,
    val hasAgy: Boolean,
    val agyPct: Int,
    val agyResets: String,
    val agyScopedLabel: String?,
    val agyScopedPct: Int,
    val agyScopedResets: String,
    val stale: Boolean,
)

private val COMPACT_MAX_WIDTH = 130.dp

// Colors: Claude in Orange, AGY in Blue.
private val claudeAccent = ColorProvider(R.color.claude_accent)
private val agyAccent = ColorProvider(R.color.agy_accent)
private fun barTrack() = ColorProvider(R.color.bar_track)

@Composable
fun UsageWidgetContent(state: WidgetState) {
    val size = LocalSize.current
    val compact = size.width < COMPACT_MAX_WIDTH
    val context = LocalContext.current

    val tap = if (state.needsLogin) {
        val intent = Intent(context, MainActivity::class.java)
        state.accountId?.let { intent.putExtra(LoginActivity.EXTRA_ACCOUNT_ID, it) }
        actionStartActivity(intent)
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
            state.needsLogin -> SignInPrompt(compact)
            !state.hasData -> Loading(compact)
            compact -> CompactLayout(state)
            else -> FullLayout(state)
        }
        if (state.hasData && state.stale) StaleDot()
    }
}

@Composable
private fun FullLayout(s: WidgetState) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lobster(20.dp)
            Spacer(GlanceModifier.width(6.dp))
            Text(
                "Claude",
                style = TextStyle(
                    color = claudeAccent,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                " & ",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Text(
                "AGY",
                style = TextStyle(
                    color = agyAccent,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        MeterRow("Claude 5H", s.fiveHourPct, s.fiveHourResets, claudeAccent)
        Spacer(GlanceModifier.height(6.dp))
        MeterRow("Claude 1W", s.sevenDayPct, s.sevenDayResets, claudeAccent)
        if (s.scopedLabel != null) {
            Spacer(GlanceModifier.height(6.dp))
            MeterRow(s.scopedLabel, s.scopedPct, s.scopedResets, claudeAccent)
        }
        if (s.hasAgy) {
            Spacer(GlanceModifier.height(6.dp))
            MeterRow("AGY 5H", s.agyPct, s.agyResets, agyAccent)
        }
        if (s.agyScopedLabel != null) {
            Spacer(GlanceModifier.height(6.dp))
            MeterRow(s.agyScopedLabel, s.agyScopedPct, s.agyScopedResets, agyAccent)
        }
    }
}

@Composable
private fun MeterRow(label: String, pct: Int, resets: String, color: ColorProvider) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.width(72.dp),
            )
            Text(
                "$pct%",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                resets,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
        Spacer(GlanceModifier.height(3.dp))
        Bar(pct, color)
    }
}

@Composable
private fun CompactLayout(s: WidgetState) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Lobster(16.dp)
        Spacer(GlanceModifier.height(4.dp))
        CompactMeter("Claude 5H", s.fiveHourPct, claudeAccent)
        Spacer(GlanceModifier.height(4.dp))
        CompactMeter("Claude 1W", s.sevenDayPct, claudeAccent)
        if (s.hasAgy) {
            Spacer(GlanceModifier.height(4.dp))
            CompactMeter("AGY 5H", s.agyPct, agyAccent)
        }
    }
}

@Composable
private fun CompactMeter(label: String, pct: Int, color: ColorProvider) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                label,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                "$pct%",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold),
            )
        }
        Spacer(GlanceModifier.height(2.dp))
        Bar(pct, color)
    }
}

@Composable
private fun Bar(pct: Int, color: ColorProvider) {
    LinearProgressIndicator(
        progress = (pct.coerceIn(0, 100)) / 100f,
        modifier = GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp),
        color = color,
        backgroundColor = barTrack(),
    )
}

@Composable
private fun Lobster(s: androidx.compose.ui.unit.Dp) {
    Image(
        provider = ImageProvider(R.drawable.ic_lobster),
        contentDescription = "Claude & AGY",
        modifier = GlanceModifier.size(s),
    )
}

@Composable
private fun SignInPrompt(compact: Boolean) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Lobster(if (compact) 18.dp else 24.dp)
        Spacer(GlanceModifier.height(6.dp))
        Text(
            if (compact) "Sign in" else "Tap to sign in",
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun Loading(compact: Boolean) {
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

